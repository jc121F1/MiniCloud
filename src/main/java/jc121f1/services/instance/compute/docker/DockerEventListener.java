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
                pendingEvents.forEach(
                        (key, future) ->
                                future.completeExceptionally(throwable)
                );

                pendingEvents.clear();
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

        CompletableFuture<Event> existing = pendingEvents.putIfAbsent(key, future);

        if (existing != null) {
            future.completeExceptionally(
                    new IllegalStateException(
                            "Already waiting for " + key
                    )
            );
        }

        return future;
    }

    @Override
    public void close() throws IOException {
        pendingEvents.forEach(
                (key, future) -> future.cancel(false)
        );

        pendingEvents.clear();

        if (callback != null) {
            callback.close();
        }
    }

    private record EventKey(
            String containerId,
            EventAction action
    ) {
    }
}
