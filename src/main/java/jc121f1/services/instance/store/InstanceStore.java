package jc121f1.services.instance.store;

import jc121f1.common.store.GenericStore;
import jc121f1.model.instance.dao.Instance;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface InstanceStore extends GenericStore<Instance> {
    /**
     * Atomically replaces the revision read by the caller and returns the incremented revision.
     * A null revision represents a legacy row; only its first update may match that absence.
     * Implementations must reject stale updates and preserve account ownership.
     */
    @Override
    CompletableFuture<Instance> update(Instance previous, Instance updated);

    /** Deletes only the caller's observed revision, including its unique-name reservation. */
    @Override
    CompletableFuture<Void> delete(Instance instance);

    CompletableFuture<Optional<Instance>> getByName(String instanceId);
}
