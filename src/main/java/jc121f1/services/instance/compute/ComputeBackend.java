package jc121f1.services.instance.compute;

import jc121f1.model.instance.dao.Instance;

import java.util.concurrent.CompletableFuture;

public interface ComputeBackend {

    CompletableFuture<Void> create(Instance instance);

    CompletableFuture<Void> start(Instance instance);

    CompletableFuture<Void> stop(Instance instance);

    CompletableFuture<Void> delete(Instance instance);
}
