package jc121f1.services.instance.compute;

import jc121f1.model.instance.ComputeStatus;
import jc121f1.model.instance.dao.Instance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface ComputeBackend extends AutoCloseable {

    CompletableFuture<Void> create(Instance instance);

    CompletableFuture<Void> start(Instance instance);

    CompletableFuture<Void> stop(Instance instance);

    CompletableFuture<Void> delete(Instance instance);

    Map<String, ComputeStatus> describeStatuses(List<Instance> instances);
}
