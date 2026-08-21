package jc121f1.services.instance.store;

import jc121f1.model.instance.dao.Instance;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface InstanceStore {
    CompletableFuture<Optional<Instance>> get(String instanceId);
    CompletableFuture<Optional<Instance>> getByName(String instanceId);
    CompletableFuture<List<Instance>> list();
    CompletableFuture<Instance> create(Instance instance);
    CompletableFuture<Instance> update(Instance instance);
    CompletableFuture<Void> delete(String instanceId);
}
