package jc121f1.services.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.exception.NotFoundException;
import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.instance.dao.DockerContainer;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.compute.ComputeOutcomeException;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.InstanceHealthEvent;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DockerComputeBackend implements ComputeBackend {

    @VisibleForTesting
    public static final String INSTANCE_LABEL_KEY = "minicloud.instance-id";

    private final Map<String, DockerContainer> instanceToContainer =
            new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> observationRevisions = new ConcurrentHashMap<>();

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "dockerClient is an injected service dependency and is intentionally shared."
    )
    private final DockerClient dockerClient;

    private final EventBus eventBus;

    private final DockerEventListener eventListener;

    private final Executor computeExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<DockerContainerEvent> eventConsumer = this::handleDockerEvent;
    private volatile boolean initialized;

    @Inject
    public DockerComputeBackend(
            DockerClient dockerClient,
            DockerEventListener eventListener,
            EventBus eventBus,
            Executor executor
    ) {
        this.dockerClient = dockerClient;
        this.eventListener = eventListener;
        this.eventBus = eventBus;
        this.computeExecutor = executor;
    }

    @Override
    public synchronized void initialize() {
        if (closed.get()) {
            throw new IllegalStateException("Docker backend is closed");
        }
        if (initialized) {
            return;
        }
        try {
            eventListener.initialize();
            reconcileContainers().join();
            eventBus.subscribe(DockerContainerEvent.class, eventConsumer);
            initialized = true;
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (Exception cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public CompletableFuture<Void> create(Instance instance) {
        requireOpen();
        String containerName = "MiniCloud-" + instance.id();
        return CompletableFuture.runAsync(() -> {
            CreateContainerCmd createCommand = dockerClient
                    .createContainerCmd("jc121f1/alpine")
                    .withHostConfig(
                            HostConfig.newHostConfig()
                                    .withCpuCount((long) instance.cpu())
                                    .withMemory(instance.memoryInBytes())
                    )
                    .withName(containerName)
                    .withLabels(Map.of(
                            INSTANCE_LABEL_KEY, instance.id()));

            CreateContainerResponse response = createCommand.exec();

            instanceToContainer.put(
                    instance.id(),
                    DockerContainer.builder()
                            .instanceId(instance.id())
                            .id(response.getId())
                            .name(containerName)
                            .status(ComputeStatus.RUNNING).build()
            );
        }, computeExecutor);
    }

    @Override
    public CompletableFuture<Void> start(Instance instance) {
        requireOpen();
        String containerId = getContainerId(instance);

        return commandAwaitingEvent(containerId, EventAction.START, ComputeStatus.RUNNING,
                () -> dockerClient.startContainerCmd(containerId).exec());
    }

    @Override
    public CompletableFuture<Void> stop(Instance instance) {
        requireOpen();
        String containerId = getContainerId(instance);

        return commandAwaitingEvent(containerId, EventAction.DIE, ComputeStatus.STOPPED,
                () -> dockerClient.stopContainerCmd(containerId).exec());
    }

    private CompletableFuture<Void> commandAwaitingEvent(String containerId, EventAction action,
                                                         ComputeStatus expected, Runnable command) {
        CompletableFuture<Event> event = eventListener.waitFor(containerId, action);
        CompletableFuture<Void> result = new CompletableFuture<>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                event.cancel(false);
            }
        });
        try {
            CompletableFuture<Void> commandCompletion = CompletableFuture.runAsync(() -> {
                if (!result.isDone() && !event.isCompletedExceptionally()) {
                    command.run();
                }
            }, computeExecutor);
            commandCompletion.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    event.completeExceptionally(failure);
                }
            });
            commandCompletion.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> event.handle((received, failure) -> failure)
                    .thenCompose(eventFailure -> observeContainerStatus(containerId)
                            .handle((status, observationFailure) -> {
                                if (observationFailure != null) {
                                    Throwable cause = eventFailure == null ? observationFailure : eventFailure;
                                    return CompletableFuture.<Void>failedFuture(new ComputeOutcomeException(
                                            "Unable to determine Docker command outcome", null, cause));
                                }
                                if (status == expected) {
                                    return CompletableFuture.<Void>completedFuture(null);
                                }
                                return CompletableFuture.<Void>failedFuture(new ComputeOutcomeException(
                                        "Docker command resulted in " + status + " instead of " + expected,
                                        status, eventFailure));
                            }).thenCompose(outcome -> outcome)))
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            result.complete(null);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (RuntimeException failure) {
            event.completeExceptionally(failure);
            result.completeExceptionally(failure);
        }
        return result;
    }

    @Override
    public CompletableFuture<Void> delete(Instance instance) {
        requireOpen();
        DockerContainer container = instanceToContainer.get(instance.id());
        if (container == null) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
            } catch (NotFoundException ignored) {
                // A previous deletion may have completed before its metadata write failed.
            }
            instanceToContainer.computeIfPresent(instance.id(), (id, current) ->
                    current.getId().equals(container.getId()) ? null : current);
        }, computeExecutor);
    }

    @Override
    public Map<String, ComputeStatus> describeStatuses(List<Instance> instances) {
        requireOpen();
        return instances.stream()
                .collect(Collectors.toMap(Instance::id,
                        instance -> {
                    DockerContainer container =  instanceToContainer.get(instance.id());
                    if  (container == null) {
                        return ComputeStatus.MISSING;
                    }
                    return container.getStatus();
                }));
    }

    @Override
    public synchronized void close() throws Exception {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // These connections belong to the backend; workloads survive process shutdown.
        // The injected executor is shared and must be shut down by its runtime owner.
        try (dockerClient; eventListener) {
            eventBus.unsubscribe(DockerContainerEvent.class, eventConsumer);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Docker backend is closed");
        }
        if (!initialized) {
            throw new IllegalStateException("Docker backend is not initialized");
        }
    }

    private String getContainerId(Instance instance) {
        Optional<DockerContainer> container = Optional.ofNullable(instanceToContainer.get(instance.id()));
        String containerId = container.map(DockerContainer::getId).orElse(null);

        if (containerId == null) {
            throw new IllegalStateException(
                    "No Docker container exists for instance " + instance.id()
            );
        }

        return containerId;
    }

    private CompletableFuture<Void> reconcileContainers() {
        return CompletableFuture.runAsync(() -> {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .exec();

            containers.forEach(container -> {
                String instanceId = container.getLabels().get(INSTANCE_LABEL_KEY);

                if (instanceId == null) {
                    return;
                }

                instanceToContainer.put(
                        instanceId,
                        DockerContainer.builder()
                                .id(container.getId())
                                .name(container.getNames()[0])
                                .instanceId(instanceId)
                                .status(ComputeStatus.fromDockerContainer(container))
                                .build()
                );
            });
        }, computeExecutor);
    }

    private void handleDockerEvent(DockerContainerEvent event) {
        if (closed.get()) {
            return;
        }
        if (event.action() == EventAction.HEALTHY
                || event.action() == EventAction.UNHEALTHY) {
            instanceToContainer.values().stream()
                    .filter(container -> container.getId().equals(event.containerId()))
                    .findFirst()
                    .ifPresent(container -> observeContainerStatus(event.containerId())
                            .thenAccept(status -> {
                                if (status == ComputeStatus.RUNNING) {
                                    publishInstanceHealthEvent(event);
                                }
                            }).exceptionally(error -> null));
            return;
        }

        boolean lifecycleEvent = switch (event.action()) {
            case START, DIE -> true;
            default -> false;
        };

        if (!lifecycleEvent) {
            return;
        }

        instanceToContainer.forEach((instanceId, container) -> {
            if (container.getId().equals(event.containerId())) {
                observeContainerStatus(event.containerId()).exceptionally(error -> null);
            }
        });
    }

    private CompletableFuture<ComputeStatus> observeContainerStatus(String containerId) {
        AtomicLong revision = observationRevisions.computeIfAbsent(containerId, ignored -> new AtomicLong());
        long observedRevision = revision.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            ComputeStatus observed;
            try {
                InspectContainerResponse response = dockerClient.inspectContainerCmd(containerId).exec();
                observed = Boolean.TRUE.equals(response.getState().getRunning())
                        ? ComputeStatus.RUNNING : ComputeStatus.STOPPED;
            } catch (NotFoundException missing) {
                observed = ComputeStatus.MISSING;
            }
            ComputeStatus finalObserved = observed;
            if (revision.get() == observedRevision) {
                instanceToContainer.forEach((instanceId, original) -> {
                    if (original.getId().equals(containerId)) {
                        instanceToContainer.computeIfPresent(instanceId, (ignored, current) -> {
                            if (!current.getId().equals(containerId)) {
                                return current;
                            }
                            return finalObserved == ComputeStatus.MISSING ? null : DockerContainer.builder()
                                    .id(current.getId())
                                    .name(current.getName())
                                    .instanceId(current.getInstanceId())
                                    .status(finalObserved)
                                    .build();
                        });
                    }
                });
            }
            return observed;
        }, computeExecutor);
    }

    private void publishInstanceHealthEvent(DockerContainerEvent event) {
        instanceToContainer.values().stream()
                .filter(container -> container.getId().equals(event.containerId()))
                .findFirst()
                .ifPresent(container -> eventBus.publish(new InstanceHealthEvent(
                        container.getInstanceId(),
                        event.action()
                )));
    }
}
