package jc121f1.service.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import jc121f1.annotations.MiniCloudTest;
import jc121f1.services.instance.compute.docker.DockerEventListener;
import jc121f1.services.instance.compute.docker.DockerContainerEvent;
import jc121f1.services.instance.compute.docker.EventAction;
import jc121f1.services.instance.events.EventBus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

@MiniCloudTest
public class DockerEventListenerTest {

    private static final String CONTAINER_ID = "container-123";
    private static final String OTHER_CONTAINER_ID = "container-456";

    @Mock
    private DockerClient dockerClient;

    @Mock
    private EventsCmd eventsCmd;

    @Mock
    private EventBus eventBus;

    private DockerEventListener eventListener;
    private ResultCallback.Adapter<Event> callback;

    @BeforeEach
    void setup() {
        Mockito.when(dockerClient.eventsCmd()).thenReturn(eventsCmd);

        eventListener = new DockerEventListener(dockerClient, eventBus);

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
        void It_should_publish_a_container_event_before_completing_a_waiter() {
            CompletableFuture<Event> future =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            Mockito.doAnswer(invocation -> {
                Assertions.assertThat(future).isNotDone();
                return null;
            }).when(eventBus).publish(Mockito.any());

            callback.onNext(event(CONTAINER_ID, "start"));

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            Mockito.verify(eventBus).publish(eventCaptor.capture());
            Assertions.assertThat(eventCaptor.getValue())
                    .isEqualTo(new DockerContainerEvent(
                            CONTAINER_ID,
                            EventAction.START
                    ));
            Assertions.assertThat(future).isCompleted();
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
        void It_should_reject_later_waits_after_stream_failure() {
            CompletableFuture<Event> first =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            callback.onError(
                    new RuntimeException("Docker event stream failed")
            );

            CompletableFuture<Event> second =
                    eventListener.waitFor(CONTAINER_ID, EventAction.START);

            Assertions.assertThat(second)
                    .isNotSameAs(first)
                    .isCompletedExceptionally();
            Assertions.assertThatThrownBy(second::join)
                    .hasRootCauseMessage("Docker event stream failed");
        }

        @Test
        void It_should_reject_pending_and_later_waits_when_the_stream_ends() {
            CompletableFuture<Event> first = eventListener.waitFor(CONTAINER_ID, EventAction.START);
            callback.onComplete();
            Assertions.assertThat(first).isCompletedExceptionally();
            Assertions.assertThat(eventListener.waitFor(CONTAINER_ID, EventAction.START))
                    .isCompletedExceptionally();
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

        @Test
        void It_should_reject_waits_after_close_and_ignore_late_events() throws IOException {
            eventListener.close();
            Assertions.assertThat(eventListener.waitFor(CONTAINER_ID, EventAction.START)).isCancelled();
            callback.onNext(Mockito.mock(Event.class));
            Mockito.verifyNoInteractions(eventBus);
        }

        @Test
        void It_should_close_the_stream_only_once() throws IOException {
            Closeable stream = Mockito.mock(Closeable.class);
            callback.onStart(stream);
            eventListener.close();
            eventListener.close();
            Mockito.verify(stream).close();
        }

        @Test
        void It_should_cancel_waits_even_when_stream_close_fails() throws IOException {
            Closeable stream = Mockito.mock(Closeable.class);
            callback.onStart(stream);
            Mockito.doThrow(new IOException("close failed")).when(stream).close();
            CompletableFuture<Event> future = eventListener.waitFor(CONTAINER_ID, EventAction.START);
            Assertions.assertThatThrownBy(eventListener::close).isInstanceOf(IOException.class);
            Assertions.assertThat(future).isCancelled();
            Assertions.assertThat(eventListener.waitFor(CONTAINER_ID, EventAction.START)).isCancelled();
        }

        @Test
        void It_should_reject_waits_registered_by_a_cancellation_callback() throws IOException {
            CompletableFuture<Event> pending = eventListener.waitFor(CONTAINER_ID, EventAction.START);
            CompletableFuture<CompletableFuture<Event>> retry = pending.handle((event, failure) ->
                    eventListener.waitFor(OTHER_CONTAINER_ID, EventAction.DIE));
            eventListener.close();
            Assertions.assertThat(retry.join()).isCancelled();
        }
    }

    @Test
    void It_should_release_cancelled_and_externally_failed_waits() {
        CompletableFuture<Event> first = eventListener.waitFor(CONTAINER_ID, EventAction.START);
        first.cancel(false);
        CompletableFuture<Event> second = eventListener.waitFor(CONTAINER_ID, EventAction.START);
        Assertions.assertThat(second).isNotDone();
        second.completeExceptionally(new IllegalStateException("command failed"));
        CompletableFuture<Event> third = eventListener.waitFor(CONTAINER_ID, EventAction.START);
        Assertions.assertThat(third).isNotDone();
        callback.onNext(event(CONTAINER_ID, "start"));
        Assertions.assertThat(third).isCompleted();
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
