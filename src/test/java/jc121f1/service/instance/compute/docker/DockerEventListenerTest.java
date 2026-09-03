package jc121f1.service.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import jc121f1.annotations.MiniCloudTest;
import jc121f1.services.instance.compute.docker.DockerEventListener;
import jc121f1.services.instance.compute.docker.EventAction;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@MiniCloudTest
public class DockerEventListenerTest {

    private static final String CONTAINER_ID = "container-123";
    private static final String OTHER_CONTAINER_ID = "container-456";

    @Mock
    private DockerClient dockerClient;

    @Mock
    private EventsCmd eventsCmd;

    private DockerEventListener eventListener;
    private ResultCallback.Adapter<Event> callback;

    @BeforeEach
    void setup() {
        Mockito.when(dockerClient.eventsCmd()).thenReturn(eventsCmd);

        eventListener = new DockerEventListener(dockerClient);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ResultCallback.Adapter<Event>> captor =
                ArgumentCaptor.forClass(ResultCallback.Adapter.class);

        Mockito.verify(eventsCmd).exec(captor.capture());

        callback = captor.getValue();
    }

    @Nested
    class When_waiting_for_an_event {

        @Test
        void It_should_complete_when_the_matching_event_is_received() {
            CompletableFuture<Event> future =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            Event event = event(CONTAINER_ID, "start");

            callback.onNext(event);

            Assertions.assertThat(future)
                    .isCompletedWithValue(event);
        }

        @Test
        void It_should_match_using_the_actor_id() {
            CompletableFuture<Event> future =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            Event event = event(CONTAINER_ID, "start");

            callback.onNext(event);

            Assertions.assertThat(future)
                    .isCompletedWithValue(event);
        }

        @Test
        void It_should_not_complete_for_a_different_container() {
            CompletableFuture<Event> future =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            callback.onNext(event(OTHER_CONTAINER_ID, "start"));

            Assertions.assertThat(future)
                    .isNotDone();
        }

        @Test
        void It_should_not_complete_for_a_different_action() {
            CompletableFuture<Event> future =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            callback.onNext(event(CONTAINER_ID, "die"));

            Assertions.assertThat(future)
                    .isNotDone();
        }

        @Test
        void It_should_ignore_unknown_actions() {
            CompletableFuture<Event> future =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            callback.onNext(event(CONTAINER_ID, "unknown"));

            Assertions.assertThat(future)
                    .isNotDone();
        }

        @Test
        void It_should_remove_the_future_after_completion() {
            CompletableFuture<Event> future =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            callback.onNext(event(CONTAINER_ID, "start"));

            Assertions.assertThat(future)
                    .isCompleted();

            CompletableFuture<Event> secondFuture =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            Assertions.assertThat(secondFuture)
                    .isNotSameAs(future);
        }

        @Test
        void It_should_reject_duplicate_waits() {
            CompletableFuture<Event> first =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            CompletableFuture<Event> second =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            Assertions.assertThat(first)
                    .isNotDone();

            Assertions.assertThatThrownBy(second::join)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class When_receiving_an_event_without_an_actor {

        @Test
        void It_should_throw() {
            CompletableFuture<Event> future =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            Event event = Mockito.mock(Event.class);

            Mockito.when(event.getAction()).thenReturn("start");
            Mockito.when(event.getActor()).thenReturn(null);

            Assertions.assertThatThrownBy(() -> callback.onNext(event))
                    .isInstanceOf(NullPointerException.class);

            Assertions.assertThat(future)
                    .isNotDone();
        }
    }

    @Nested
    class When_the_event_stream_fails {

        @Test
        void It_should_complete_pending_futures_exceptionally() {
            CompletableFuture<Event> first =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            CompletableFuture<Event> second =
                    eventListener.waitFor(OTHER_CONTAINER_ID, EventAction.DIE);

            RuntimeException exception =
                    new RuntimeException("Docker event stream failed");

            callback.onError(exception);

            Assertions.assertThat(first)
                    .isCompletedExceptionally();

            Assertions.assertThat(second)
                    .isCompletedExceptionally();
        }

        @Test
        void It_should_clear_pending_events() {
            CompletableFuture<Event> first =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            callback.onError(
                    new RuntimeException("Docker event stream failed")
            );

            CompletableFuture<Event> second =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            Assertions.assertThat(second)
                    .isNotSameAs(first)
                    .isNotDone();
        }
    }

    @Nested
    class When_closing {

        @Test
        void It_should_cancel_pending_futures() throws IOException {
            CompletableFuture<Event> future =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            eventListener.close();

            Assertions.assertThat(future)
                    .isCancelled();
        }
    }

    @Test
    void It_should_keep_the_original_wait_when_a_duplicate_is_requested() {
        CompletableFuture<Event> first =
                eventListener.waitFor(CONTAINER_ID, EventAction.START);

        CompletableFuture<Event> second =
                eventListener.waitFor(CONTAINER_ID, EventAction.START);

        callback.onNext(event(CONTAINER_ID, "start"));

        Assertions.assertThat(first)
                .isCompleted();

        Assertions.assertThat(second)
                .isCompletedExceptionally();
    }

    private Event event(String containerId, String action) {
        Event event = Mockito.mock(Event.class);
        EventActor actor = Mockito.mock(EventActor.class);

        Mockito.when(event.getAction()).thenReturn(action);
        Mockito.lenient().when(event.getActor()).thenReturn(actor);
        Mockito.lenient().when(actor.getId()).thenReturn(containerId);

        return event;
    }
}