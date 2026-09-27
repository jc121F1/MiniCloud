package jc121f1.services.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import com.google.common.base.Preconditions;
import jc121f1.services.instance.events.EventBus;

import javax.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DockerEventListener implements AutoCloseable {

    private final Map<EventKey, CompletableFuture<Event>> pendingEvents =
            new ConcurrentHashMap<>();

    private final DockerClient dockerClient;
    private final EventBus eventBus;
    private volatile ResultCallback.Adapter<Event> callback;
    private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Duration eventTimeout;
    private final ScheduledExecutorService reconnectExecutor;
    private final long initialReconnectDelayMillis;
    private final long maximumReconnectDelayMillis;
    private final AtomicReference<Thread> reconnectThread = new AtomicReference<>();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private volatile long reconnectDelayMillis;
    private volatile long generation;
    private volatile boolean initialized;

    @Inject
    public DockerEventListener(DockerClient dockerClient, EventBus eventBus) {
        this(dockerClient, eventBus, Duration.ofSeconds(60), Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    DockerEventListener(DockerClient dockerClient, EventBus eventBus, Duration eventTimeout) throws IllegalArgumentException {
        this(dockerClient, eventBus, eventTimeout, Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    DockerEventListener(DockerClient dockerClient, EventBus eventBus, Duration eventTimeout,
                        Duration initialReconnectDelay, Duration maximumReconnectDelay) {
        if (Objects.requireNonNull(eventTimeout, "eventTimeout").toMillis() <= 0) {
            throw new IllegalArgumentException("Event timeout must be at least one millisecond");
        }
        if (initialReconnectDelay.isNegative() || initialReconnectDelay.isZero()
                || maximumReconnectDelay.compareTo(initialReconnectDelay) < 0) {
            throw new IllegalArgumentException("Invalid Docker event reconnect delays");
        }
        this.dockerClient = dockerClient;
        this.eventBus = eventBus;
        this.eventTimeout = eventTimeout;
        this.initialReconnectDelayMillis = initialReconnectDelay.toMillis();
        this.maximumReconnectDelayMillis = maximumReconnectDelay.toMillis();
        this.reconnectDelayMillis = initialReconnectDelayMillis;
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "docker-event-reconnect");
            thread.setDaemon(true);
            reconnectThread.set(thread);
            return thread;
        };
        reconnectExecutor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public synchronized void initialize() {
        if (closed.get()) {
            throw new IllegalStateException("Docker event listener is closed");
        }
        if (initialized) {
            return;
        }
        try {
            connect();
            initialized = true;
        } catch (RuntimeException | Error failure) {
            reconnectExecutor.shutdownNow();
            throw failure;
        }
    }

    private synchronized void connect() {
        if (closed.get()) {
            return;
        }
        long thisGeneration = ++generation;
        ResultCallback.Adapter<Event> newCallback = new ResultCallback.Adapter<>() {

            @Override
            public void onNext(Event event) {
                if (closed.get() || thisGeneration != generation) {
                    return;
                }
                reconnectDelayMillis = initialReconnectDelayMillis;
                Optional<EventAction> action =
                        EventAction.fromValue(event.getAction());

                if (action.isEmpty()) {
                    return;
                }

                EventActor actor = Preconditions.checkNotNull(event.getActor(),
                        "Docker event actor must not be null");
                EventKey key = new EventKey(actor.getId(), action.get());

                eventBus.publish(new DockerContainerEvent(
                        actor.getId(),
                        action.get()
                ));

                CompletableFuture<Event> future = pendingEvents.remove(key);

                if (future != null) {
                    future.complete(event);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                streamFailed(thisGeneration, throwable);
            }

            @Override
            public void onComplete() {
                streamFailed(thisGeneration, new IllegalStateException("Docker event stream ended"));
            }
        };

        try {
            callback = newCallback;
            terminalFailure.set(null);
            dockerClient.eventsCmd().exec(newCallback);
        } catch (RuntimeException | Error failure) {
            // A failed startup may already have opened a stream.
            try {
                newCallback.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void streamFailed(long failedGeneration, Throwable failure) {
        if (closed.get() || failedGeneration != generation) {
            return;
        }
        terminalFailure.set(failure);
        pendingEvents.forEach((key, future) -> future.completeExceptionally(failure));
        if (reconnectScheduled.compareAndSet(false, true)) {
            long delay = reconnectDelayMillis;
            reconnectDelayMillis = Math.min(maximumReconnectDelayMillis, Math.max(delay + 1, delay * 2));
            try {
                reconnectExecutor.schedule(() -> {
                    reconnectScheduled.set(false);
                    if (!closed.get()) {
                        try {
                            connect();
                        } catch (RuntimeException | Error failureToReconnect) {
                            streamFailed(generation, failureToReconnect);
                        }
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                reconnectScheduled.set(false);
            }
        }
    }

    public CompletableFuture<Event> waitFor(
            String containerId,
            EventAction action
    ) {
        if (!initialized && !closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Docker event listener is not initialized"));
        }
        EventKey key = new EventKey(containerId, action);

        CompletableFuture<Event> future = new CompletableFuture<>();
        Throwable failure = terminalFailure.get();
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }

        CompletableFuture<Event> existing = pendingEvents.putIfAbsent(key, future);

        if (existing != null) {
            future.completeExceptionally(
                    new IllegalStateException(
                            "Already waiting for " + key
                    )
            );
            return future;
        }

        // Remove waits completed by command failures or cancellation, not only Docker events.
        future.whenComplete((event, error) -> pendingEvents.remove(key, future));
        failure = terminalFailure.get();
        if (failure != null) {
            future.completeExceptionally(failure);
        }
        applyDeadline(future);

        return future;
    }

    void applyDeadline(CompletableFuture<Event> future) {
        // CompletableFuture cancels its scheduled timeout when the wait finishes early.
        future.orTimeout(eventTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        terminate(new CancellationException("Docker event listener is closed"));
        reconnectExecutor.shutdownNow();
        Thread thread = reconnectThread.get();
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (callback != null) {
            callback.close();
        }
    }

    private void terminate(Throwable failure) {
        if (terminalFailure.compareAndSet(null, failure)) {
            pendingEvents.forEach((key, future) -> future.completeExceptionally(failure));
        }
    }

    private record EventKey(
            String containerId,
            EventAction action
    ) {
    }
}
