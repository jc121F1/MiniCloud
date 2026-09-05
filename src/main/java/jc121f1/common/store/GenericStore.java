package jc121f1.common.store;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface GenericStore<T> {

    CompletableFuture<Optional<T>> get(String id);

    CompletableFuture<List<T>> list();

    CompletableFuture<T> create(T item);

    CompletableFuture<T> update(T previous, T updated);

    CompletableFuture<Void> delete(T item);
}