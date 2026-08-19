package jc121f1.services.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.HostConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;

import javax.inject.Inject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DockerComputeBackend implements ComputeBackend {

    private final Map<String, String> instanceToContainer =
            new ConcurrentHashMap<>();

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "dockerClient is an injected service dependency and is intentionally shared."
    )
    private final DockerClient dockerClient;

    private final DockerEventListener eventListener;

    @Inject
    public DockerComputeBackend(
            DockerClient dockerClient,
            DockerEventListener eventListener
    ) {
        this.dockerClient = dockerClient;
        this.eventListener = eventListener;
    }

    @Override
    public CompletableFuture<Void> create(Instance instance) {
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

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> start(Instance instance) {
        String containerId = getContainerId(instance);

        CompletableFuture<Event> future =
                eventListener.waitFor(containerId, EventAction.START);

        eventListener.waitFor(containerId, EventAction.UNHEALTHY);

        try {
            dockerClient.startContainerCmd(containerId).exec();
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future.thenApply(event -> null);
    }

    @Override
    public CompletableFuture<Void> stop(Instance instance) {
        String containerId = getContainerId(instance);

        CompletableFuture<Event> future =
                eventListener.waitFor(containerId, EventAction.DIE);

        try {
            dockerClient.stopContainerCmd(containerId).exec();
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future.thenApply(event -> null);
    }

    @Override
    public CompletableFuture<Void> delete(Instance instance) {
        String containerId = getContainerId(instance);

        dockerClient.removeContainerCmd(containerId).exec();

        instanceToContainer.remove(instance.getId());

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() throws Exception {
        eventListener.close();

        for (String containerId : instanceToContainer.values()) {
            try {
                dockerClient.stopContainerCmd(containerId).exec();
            } catch (Exception ignored) {
                // Container may already be stopped or removed.
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