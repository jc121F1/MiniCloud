package jc121f1.services.instance.store;

import jc121f1.common.store.GenericStore;
import jc121f1.model.instance.dao.Instance;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface InstanceStore extends GenericStore<Instance> {
    CompletableFuture<Optional<Instance>> getByName(String instanceId);
}
