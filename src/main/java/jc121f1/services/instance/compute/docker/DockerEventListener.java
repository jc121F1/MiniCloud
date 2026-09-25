package jc121f1.services.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import com.google.common.base.Preconditions;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.services.instance.events.EventBus;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DockerEventListener implements AutoCloseable {

    private final Map<EventKey, CompletableFuture<Event>> pendingEvents =
            new ConcurrentHashMap<>();

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "dockerClient is an injected service dependency and is intentionally shared."
    )
    private final DockerClient dockerClient;
    private final EventBus eventBus;
    private ResultCallback.Adapter<Event> callback;
    private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Inject
    public DockerEventListener(DockerClient dockerClient, EventBus eventBus) {
        this.dockerClient = dockerClient;
        this.eventBus = eventBus;
        start();
    }

    private void start() {
        callback = new ResultCallback.Adapter<>() {

            @Override
            public void onNext(Event event) {
                if (terminalFailure.get() != null) {
                    return;
                }
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
                terminate(throwable);
            }

            @Override
            public void onComplete() {
                terminate(new IllegalStateException("Docker event stream ended"));
            }
        };

        dockerClient.eventsCmd().exec(callback);
    }

    public CompletableFuture<Event> waitFor(
            String containerId,
            EventAction action
    ) {
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

        return future;
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        terminate(new CancellationException("Docker event listener is closed"));
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
