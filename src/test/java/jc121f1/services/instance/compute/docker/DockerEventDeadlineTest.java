package jc121f1.services.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeOutcomeException;
import jc121f1.services.instance.events.EventBus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class DockerEventDeadlineTest {
    private final DockerClient client = Mockito.mock(DockerClient.class);
    private final EventBus events = Mockito.mock(EventBus.class);
    private final Instance instance = Mockito.mock(Instance.class);
    private ManualDeadlineListener listener;
    private ResultCallback.Adapter<Event> callback;

    @Test
    void expiredWaitReleasesItsRegistrationWithoutRemovingARetry() throws Exception {
        try (DockerComputeBackend ignored = backend(Runnable::run)) {
            CompletableFuture<Event> first = listener.waitFor("c-1", EventAction.DIE);
            expire(first);
            Assertions.assertThatThrownBy(first::join).hasCauseInstanceOf(TimeoutException.class);
            CompletableFuture<Event> retry = listener.waitFor("c-1", EventAction.DIE);
            expire(first);
            Assertions.assertThat(retry).isNotDone();
            callback.onNext(stoppedEvent());
            Assertions.assertThat(retry).isCompleted();
            expire(retry);
            Assertions.assertThat(retry).isCompletedWithValue(retry.join());
        }
    }

    @Test
    void queuedCommandDoesNotRunAfterItsWaitExpires() throws Exception {
        AtomicBoolean queue = new AtomicBoolean();
        AtomicReference<Runnable> task = new AtomicReference<>();
        try (DockerComputeBackend backend = backend(queueingExecutor(queue, task))) {
            queue.set(true);
            CompletableFuture<Void> operation = backend.stop(instance);
            expire(listener.lastWait);
            Assertions.assertThat(operation).isNotDone();
            task.get().run();
            Assertions.assertThatThrownBy(operation::join).hasCauseInstanceOf(ComputeOutcomeException.class);
            Mockito.verify(client, Mockito.never()).stopContainerCmd(Mockito.anyString());
        }
    }

    @Test
    void timeoutWaitsForAnAlreadyRunningCommandToFinish() throws Exception {
        AtomicBoolean queue = new AtomicBoolean();
        AtomicReference<Runnable> task = new AtomicReference<>();
        try (DockerComputeBackend backend = backend(queueingExecutor(queue, task))) {
            StopContainerCmd command = Mockito.mock(StopContainerCmd.class);
            Mockito.when(client.stopContainerCmd("c-1")).thenReturn(command);
            queue.set(true);
            CompletableFuture<Void> operation = backend.stop(instance);
            Mockito.doAnswer(invocation -> {
                expire(listener.lastWait);
                Assertions.assertThat(operation).isNotDone();
                return null;
            }).when(command).exec();
            task.get().run();
            Assertions.assertThatThrownBy(operation::join).hasCauseInstanceOf(ComputeOutcomeException.class);
            Mockito.verify(command).exec();
        }
    }

    @Test
    void cancellingAnOperationReleasesItsWaitAndSkipsItsQueuedCommand() throws Exception {
        AtomicBoolean queue = new AtomicBoolean();
        AtomicReference<Runnable> task = new AtomicReference<>();
        try (DockerComputeBackend backend = backend(queueingExecutor(queue, task))) {
            queue.set(true);
            CompletableFuture<Void> operation = backend.stop(instance);
            CompletableFuture<Event> wait = listener.lastWait;
            operation.cancel(false);
            Assertions.assertThat(wait).isCancelled();
            CompletableFuture<Event> retry = listener.waitFor("c-1", EventAction.DIE);
            task.get().run();
            Assertions.assertThat(retry).isNotDone();
            Mockito.verify(client, Mockito.never()).stopContainerCmd(Mockito.anyString());
            retry.cancel(false);
        }
    }

    @Test
    void rejectsNonpositiveAndSubmillisecondDeadlinesBeforeOpeningTheStream() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1))) {
            Assertions.assertThatThrownBy(() -> new DockerEventListener(client, events, timeout))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        Mockito.verifyNoInteractions(client);
    }

    private Executor queueingExecutor(AtomicBoolean queue, AtomicReference<Runnable> task) {
        return work -> {
            if (queue.get()) {
                task.set(work);
            } else {
                work.run();
            }
        };
    }

    private void expire(CompletableFuture<Event> future) {
        future.completeExceptionally(new TimeoutException("Docker event deadline expired"));
    }

    @SuppressWarnings("unchecked")
    private DockerComputeBackend backend(Executor executor) {
        EventsCmd eventCommand = Mockito.mock(EventsCmd.class);
        Mockito.when(client.eventsCmd()).thenReturn(eventCommand);
        listener = new ManualDeadlineListener(client, events);
        listener.initialize();
        ArgumentCaptor<ResultCallback.Adapter<Event>> captor = ArgumentCaptor.forClass(ResultCallback.Adapter.class);
        Mockito.verify(eventCommand).exec(captor.capture());
        callback = captor.getValue();
        Container container = Mockito.mock(Container.class);
        Mockito.when(container.getId()).thenReturn("c-1");
        Mockito.when(container.getLabels()).thenReturn(Map.of(DockerComputeBackend.INSTANCE_LABEL_KEY, "i-1"));
        Mockito.when(container.getNames()).thenReturn(new String[] {"MiniCloud-i-1"});
        Mockito.when(container.getState()).thenReturn("running");
        ListContainersCmd list = Mockito.mock(ListContainersCmd.class);
        Mockito.when(client.listContainersCmd()).thenReturn(list);
        Mockito.when(list.withShowAll(true)).thenReturn(list);
        Mockito.when(list.exec()).thenReturn(List.of(container));
        Mockito.when(instance.id()).thenReturn("i-1");
        DockerComputeBackend backend = new DockerComputeBackend(client, listener, events, executor);
        backend.initialize();
        return backend;
    }

    private Event stoppedEvent() {
        Event event = Mockito.mock(Event.class);
        EventActor actor = Mockito.mock(EventActor.class);
        Mockito.when(event.getAction()).thenReturn("die");
        Mockito.when(event.getActor()).thenReturn(actor);
        Mockito.when(actor.getId()).thenReturn("c-1");
        return event;
    }

    private static final class ManualDeadlineListener extends DockerEventListener {
        private CompletableFuture<Event> lastWait;

        private ManualDeadlineListener(DockerClient client, EventBus events) {
            super(client, events);
        }

        @Override
        void applyDeadline(CompletableFuture<Event> future) {
            lastWait = future;
        }
    }
}
