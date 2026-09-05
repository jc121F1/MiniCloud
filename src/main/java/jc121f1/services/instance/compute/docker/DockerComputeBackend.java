package jc121f1.services.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.HostConfig;
import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.instance.dao.DockerContainer;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.InstanceHealthEvent;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
public class DockerComputeBackend implements ComputeBackend {

    @VisibleForTesting
    public static final String INSTANCE_LABEL_KEY = "minicloud.instance-id";

    private final Map<String, DockerContainer> instanceToContainer =
            new ConcurrentHashMap<>();

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "dockerClient is an injected service dependency and is intentionally shared."
    )
    private final DockerClient dockerClient;

    private final EventBus eventBus;

    private final DockerEventListener eventListener;

    private final Executor computeExecutor;

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
        this.reconcileContainers().join();
        this.eventBus.subscribe(DockerContainerEvent.class, this::handleDockerEvent);
    }

    @Override
    public CompletableFuture<Void> create(Instance instance) {
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
        String containerId = getContainerId(instance);

        CompletableFuture<Event> startFuture =
                eventListener.waitFor(containerId, EventAction.START);

        CompletableFuture<Void> startCommand = CompletableFuture.runAsync(() -> {
            try {
                dockerClient.startContainerCmd(containerId).exec();
            } catch (Exception e) {
                startFuture.completeExceptionally(e);
            }
        }, computeExecutor);

        return startCommand.thenCompose(ignored -> startFuture)
                .thenApply(ignoredEvent -> null);
    }

    @Override
    public CompletableFuture<Void> stop(Instance instance) {
        String containerId = getContainerId(instance);

        CompletableFuture<Event> stopped =
                eventListener.waitFor(containerId, EventAction.DIE);

        CompletableFuture<Void> command = CompletableFuture.runAsync(() ->
                dockerClient.stopContainerCmd(containerId).exec(), computeExecutor);

        return command.thenCompose(ignored ->
                stopped.thenApply(ignoredEvent -> null));
    }

    @Override
    public CompletableFuture<Void> delete(Instance instance) {
        String containerId = getContainerId(instance);

        return CompletableFuture.runAsync(() -> {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();

            instanceToContainer.remove(instance.id());
        }, computeExecutor);
    }

    @Override
    public Map<String, ComputeStatus> describeStatuses(List<Instance> instances) {
        return instances.stream()
                .collect(Collectors.toMap(Instance::id,
                        instance -> {
                    DockerContainer container =  instanceToContainer.get(instance.id());
                    if  (container == null) {
                        return ComputeStatus.MISSING;
                    }
                    return instanceToContainer.get(instance.id()).getStatus();
                }));
    }

    @Override
    public void close() throws Exception {
        eventListener.close();

        instanceToContainer.values().stream().map(DockerContainer::getId).forEach(containerId -> {
            try {
                log.info("Closing container {}", containerId);
                dockerClient.stopContainerCmd(containerId).exec();
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                // Container may already be stopped or removed.
                log.warn("Failed to close Docker client", e);
            }
        });

        dockerClient.close();
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
        if (event.action() == EventAction.HEALTHY
                || event.action() == EventAction.UNHEALTHY) {
            publishInstanceHealthEvent(event);
            return;
        }

        ComputeStatus status = switch (event.action()) {
            case START -> ComputeStatus.RUNNING;
            case DIE -> ComputeStatus.STOPPED;
            default -> null;
        };

        if (status == null) {
            return;
        }

        instanceToContainer.forEach((instanceId, container) -> {
            if (container.getId().equals(event.containerId())) {
                instanceToContainer.computeIfPresent(instanceId,
                        (ignored, current) -> {
                            if (!current.getId().equals(event.containerId())) {
                                return current;
                            }

                            return DockerContainer.builder()
                                    .id(current.getId())
                                    .name(current.getName())
                                    .instanceId(current.getInstanceId())
                                    .status(status)
                                    .build();
                        });
            }
        });
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
