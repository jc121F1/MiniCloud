package jc121f1.services.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.HostConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.events.EventBus;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
public class DockerComputeBackend implements ComputeBackend {

    private final Map<String, String> instanceToContainer =
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
    }

    @Override
    public CompletableFuture<Void> create(Instance instance) {
        return CompletableFuture.runAsync(() -> {
            CreateContainerCmd createCommand = dockerClient
                    .createContainerCmd("jc121f1/alpine")
                    .withHostConfig(
                            HostConfig.newHostConfig()
                                    .withCpuCount((long) instance.getCpu())
                                    .withMemory(instance.memoryInBytes())
                    )
                    .withName("MiniCloud-" + instance.getId());

            CreateContainerResponse response = createCommand.exec();

            instanceToContainer.put(
                    instance.getId(),
                    response.getId()
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
                .thenApply(event -> null);
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

            instanceToContainer.remove(instance.getId());
        }, computeExecutor);
    }

    @Override
    public void close() throws Exception {
        eventListener.close();

        for (String containerId : instanceToContainer.values()) {
            try {
                log.info("Closing container " + containerId);
                dockerClient.stopContainerCmd(containerId).exec();
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                // Container may already be stopped or removed.
                log.warn("Failed to close Docker client", e);
            }
        }

        dockerClient.close();
    }

    private String getContainerId(Instance instance) {
        String containerId = instanceToContainer.get(instance.getId());

        if (containerId == null) {
            throw new IllegalStateException(
                    "No Docker container exists for instance " + instance.getId()
            );
        }

        return containerId;
    }
}