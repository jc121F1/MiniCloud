package jc121f1.service.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.docker.DockerComputeBackend;
import jc121f1.services.instance.compute.docker.DockerEventListener;
import jc121f1.services.instance.compute.docker.EventAction;
import jc121f1.services.instance.events.EventBus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

class DockerBackendEventLifecycleTest {
    private final DockerClient client = Mockito.mock(DockerClient.class);
    private final EventBus events = Mockito.mock(EventBus.class);
    private final Instance instance = Mockito.mock(Instance.class);
    private DockerEventListener listener;
    private ResultCallback.Adapter<Event> callback;

    @Test
    void failedStopReleasesTheWaitSoARetryCanSucceed() throws Exception {
        try (DockerComputeBackend backend = backend(Runnable::run)) {
            StopContainerCmd command = Mockito.mock(StopContainerCmd.class);
            Mockito.when(client.stopContainerCmd("container-1")).thenReturn(command);
            Mockito.when(command.exec()).thenThrow(new IllegalStateException("stop failed")).thenReturn(null);

            Assertions.assertThatThrownBy(() -> backend.stop(instance).join())
                    .hasRootCauseMessage("stop failed");
            CompletableFuture<Void> retry = backend.stop(instance);
            Assertions.assertThat(retry).isNotDone();
            callback.onNext(stoppedEvent());
            Assertions.assertThat(retry).isCompleted();
            Mockito.verify(command, Mockito.times(2)).exec();
        }
    }

    @Test
    void executorRejectionReleasesTheRegisteredWait() throws Exception {
        AtomicBoolean rejecting = new AtomicBoolean();
        Executor executor = task -> {
            if (rejecting.get()) {
                throw new RejectedExecutionException("executor stopped");
            }
            task.run();
        };
        try (DockerComputeBackend backend = backend(executor)) {
            rejecting.set(true);
            Assertions.assertThatThrownBy(() -> backend.stop(instance).join())
                    .hasCauseInstanceOf(RejectedExecutionException.class);
            CompletableFuture<Event> retry = listener.waitFor("container-1", EventAction.DIE);
            Assertions.assertThat(retry).isNotDone();
            Mockito.verify(client, Mockito.never()).stopContainerCmd(Mockito.anyString());
            retry.cancel(false);
        }
    }

    @SuppressWarnings("unchecked")
    private DockerComputeBackend backend(Executor executor) {
        EventsCmd eventCommand = Mockito.mock(EventsCmd.class);
        Mockito.when(client.eventsCmd()).thenReturn(eventCommand);
        listener = new DockerEventListener(client, events);
        ArgumentCaptor<ResultCallback.Adapter<Event>> captor = ArgumentCaptor.forClass(ResultCallback.Adapter.class);
        Mockito.verify(eventCommand).exec(captor.capture());
        callback = captor.getValue();

        Container container = Mockito.mock(Container.class);
        Mockito.when(container.getId()).thenReturn("container-1");
        Mockito.when(container.getLabels()).thenReturn(Map.of(DockerComputeBackend.INSTANCE_LABEL_KEY, "i-1"));
        Mockito.when(container.getNames()).thenReturn(new String[] {"MiniCloud-i-1"});
        Mockito.when(container.getState()).thenReturn("running");
        ListContainersCmd list = Mockito.mock(ListContainersCmd.class);
        Mockito.when(client.listContainersCmd()).thenReturn(list);
        Mockito.when(list.withShowAll(true)).thenReturn(list);
        Mockito.when(list.exec()).thenReturn(List.of(container));
        Mockito.when(instance.id()).thenReturn("i-1");
        return new DockerComputeBackend(client, listener, events, executor);
    }

    private Event stoppedEvent() {
        Event event = Mockito.mock(Event.class);
        EventActor actor = Mockito.mock(EventActor.class);
        Mockito.when(event.getAction()).thenReturn("die");
        Mockito.when(event.getActor()).thenReturn(actor);
        Mockito.when(actor.getId()).thenReturn("container-1");
        return event;
    }
}
