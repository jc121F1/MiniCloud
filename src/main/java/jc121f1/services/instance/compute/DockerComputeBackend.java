package jc121f1.services.instance.compute;

import com.github.dockerjava.api.DockerClient;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.instance.dao.Instance;

import javax.inject.Inject;
import java.util.concurrent.CompletableFuture;

public class DockerComputeBackend implements ComputeBackend {

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "dockerClient is an injected service dependency and is intentionally shared."
    )
    private final DockerClient dockerClient;

    @Inject
    public DockerComputeBackend(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public CompletableFuture<Void> create(Instance instance) {
        return null;
    }

    @Override
    public CompletableFuture<Void> start(Instance instance) {
        return null;
    }

    @Override
    public CompletableFuture<Void> stop(Instance instance) {
        return null;
    }

    @Override
    public CompletableFuture<Void> delete(Instance instance) {
        return null;
    }
}
