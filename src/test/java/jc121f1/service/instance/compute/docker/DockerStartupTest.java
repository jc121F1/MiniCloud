package jc121f1.service.instance.compute.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.docker.DockerComputeBackend;
import jc121f1.services.instance.compute.docker.DockerEventListener;
import jc121f1.services.instance.compute.docker.EventAction;
import jc121f1.services.instance.events.EventBus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

class DockerStartupTest {
    private final DockerClient client = Mockito.mock(DockerClient.class);
    private final EventBus events = Mockito.mock(EventBus.class);

    @Test
    void constructorsPerformNoIoAndOperationsRequireInitialization() throws Exception {
        DockerEventListener listener = new DockerEventListener(client, events);
        try (DockerComputeBackend backend = new DockerComputeBackend(client, listener, events, Runnable::run)) {
            Mockito.verifyNoInteractions(client, events);
            Instance instance = Mockito.mock(Instance.class);
            Assertions.assertThatThrownBy(() -> backend.create(instance)).hasMessage("Docker backend is not initialized");
            Assertions.assertThatThrownBy(() -> backend.start(instance)).hasMessage("Docker backend is not initialized");
            Assertions.assertThatThrownBy(() -> backend.stop(instance)).hasMessage("Docker backend is not initialized");
            Assertions.assertThatThrownBy(() -> backend.delete(instance)).hasMessage("Docker backend is not initialized");
            Assertions.assertThatThrownBy(() -> backend.describeStatuses(List.of()))
                    .hasMessage("Docker backend is not initialized");
            Assertions.assertThatThrownBy(() -> listener.waitFor("c-1", EventAction.START).join())
                    .hasRootCauseMessage("Docker event listener is not initialized");
            Mockito.verifyNoInteractions(client, events, instance);
        }
        Mockito.verify(client).close();
    }

    @Test
    void backendStartsListenerAndDiscoversContainersOnlyOnce() throws Exception {
        EventsCmd stream = Mockito.mock(EventsCmd.class);
        Mockito.when(client.eventsCmd()).thenReturn(stream);
        ListContainersCmd list = listCommand();
        Mockito.when(list.exec()).thenReturn(List.of());
        DockerEventListener listener = new DockerEventListener(client, events);
        try (DockerComputeBackend backend = new DockerComputeBackend(client, listener, events, Runnable::run)) {
            backend.initialize();
            backend.initialize();
            listener.initialize();
            Mockito.verify(stream).exec(Mockito.any());
            Mockito.verify(list).exec();
            Assertions.assertThat(backend.describeStatuses(List.of())).isEmpty();
        }
        Mockito.verify(client).close();
        Mockito.verify(client, Mockito.never()).removeContainerCmd(Mockito.anyString());
        Mockito.verify(client, Mockito.never()).stopContainerCmd(Mockito.anyString());
    }

    @Test
    void failedDiscoveryClosesOwnedResourcesAndPreventsRetry() throws Exception {
        DockerEventListener listener = Mockito.mock(DockerEventListener.class);
        ListContainersCmd list = listCommand();
        Mockito.when(list.exec()).thenThrow(new IllegalStateException("Docker unavailable"));
        DockerComputeBackend backend = new DockerComputeBackend(client, listener, events, Runnable::run);
        Assertions.assertThatThrownBy(backend::initialize).hasRootCauseMessage("Docker unavailable");
        Mockito.verify(listener).close();
        Mockito.verify(client).close();
        backend.close();
        Assertions.assertThatThrownBy(backend::initialize).hasMessage("Docker backend is closed");
        Mockito.verify(client).close();
        Mockito.verify(client, Mockito.never()).removeContainerCmd(Mockito.anyString());
    }

    @Test
    void failedStreamStartupDoesNotBeginContainerDiscovery() throws Exception {
        DockerEventListener listener = Mockito.mock(DockerEventListener.class);
        IllegalStateException failure = new IllegalStateException("stream unavailable");
        Mockito.doThrow(failure).when(listener).initialize();
        DockerComputeBackend backend = new DockerComputeBackend(client, listener, events, Runnable::run);
        Assertions.assertThatThrownBy(backend::initialize).isSameAs(failure);
        Mockito.verify(client, Mockito.never()).listContainersCmd();
        Mockito.verify(listener).close();
        Mockito.verify(client).close();
    }

    @Test
    void closeBeforeInitializationPreventsOpeningConnections() throws Exception {
        DockerEventListener listener = new DockerEventListener(client, events);
        DockerComputeBackend backend = new DockerComputeBackend(client, listener, events, Runnable::run);
        backend.close();
        Assertions.assertThatThrownBy(backend::initialize).hasMessage("Docker backend is closed");
        Assertions.assertThatThrownBy(listener::initialize).hasMessage("Docker event listener is closed");
        Mockito.verify(client, Mockito.never()).eventsCmd();
        Mockito.verify(client, Mockito.never()).listContainersCmd();
    }

    private ListContainersCmd listCommand() {
        ListContainersCmd list = Mockito.mock(ListContainersCmd.class);
        Mockito.when(client.listContainersCmd()).thenReturn(list);
        Mockito.when(list.withShowAll(true)).thenReturn(list);
        return list;
    }
}
