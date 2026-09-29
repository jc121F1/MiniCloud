package jc121f1.runtime;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns resources as soon as providers create them, including during failed graph construction. */
@Slf4j
@Singleton
public final class RuntimeResources implements AutoCloseable {
    public enum Phase {
        BACKEND,
        WORKERS,
        CLIENTS
    }

    private final List<Entry> entries = new ArrayList<>();
    private boolean closed;

    @Inject
    public RuntimeResources() {
    }

    public synchronized <T extends AutoCloseable> T own(T resource, Phase phase) {
        if (closed) {
            closeResource(resource);
            throw new IllegalStateException("Runtime resources are closed");
        }
        entries.add(new Entry(resource, phase));
        return resource;
    }

    /** Transfer children to a successfully constructed composite that closes them itself. */
    public synchronized <T extends AutoCloseable> T ownComposite(T resource, AutoCloseable... children) {
        own(resource, Phase.BACKEND);
        entries.removeIf(entry -> Arrays.stream(children).anyMatch(child -> child == entry.resource()));
        return resource;
    }

    public synchronized ExecutorService ownExecutor(ExecutorService executor) {
        own(() -> stopExecutor(executor), Phase.WORKERS);
        return executor;
    }

    private static void stopExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Runtime executor did not terminate after shutdown deadline");
                }
            }
        } catch (InterruptedException error) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        List<Entry> owned;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            owned = new ArrayList<>(entries);
            entries.clear();
        }
        Collections.reverse(owned);
        for (Phase phase : Phase.values()) {
            for (Entry entry : owned) {
                if (entry.phase() == phase) {
                    closeResource(entry.resource());
                }
            }
        }
    }

    private static void closeResource(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception error) {
            log.warn("Unable to close runtime resource", error);
        }
    }

    private record Entry(AutoCloseable resource, Phase phase) {
    }
}
