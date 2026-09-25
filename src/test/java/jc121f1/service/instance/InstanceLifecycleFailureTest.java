package jc121f1.service.instance;

import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.request.DeleteInstanceRequest;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.exceptions.ConflictException;
import jc121f1.services.instance.store.InstanceStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

class InstanceLifecycleFailureTest {
    private final InstanceStore store = Mockito.mock(InstanceStore.class);
    private final ComputeBackend backend = Mockito.mock(ComputeBackend.class);
    private final AuthenticatedSession caller = new AuthenticatedSession("a-1", "u-1", Session.SubjectType.USER);
    private final Instance stopped = Instance.builder().id("i-1").name("example").accountId("a-1")
            .state(InstanceState.STOPPED).revision(1L).build();
    private InstanceServiceImpl service;

    @BeforeEach
    void setup() {
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of()));
        Mockito.when(store.get("i-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(stopped)));
        service = new InstanceServiceImpl(Clock.systemUTC(), backend, Mockito.mock(EventBus.class), store,
                Mockito.mock(AuthorizationService.class));
    }

    @Test
    void losingStartReservationDoesNotReachBackend() {
        Mockito.when(store.update(Mockito.any(), Mockito.any()))
                .thenReturn(CompletableFuture.failedFuture(new ConflictException("stale")));
        Assertions.assertThatThrownBy(() -> service.start(caller, startRequest()))
                .isInstanceOf(ConflictException.class);
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void staleCompletionDoesNotAttemptAnotherMissingWrite() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Instance starting = stopped.toBuilder().state(InstanceState.STARTING).revision(2L).build();
        Mockito.when(store.update(Mockito.any(), Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(starting))
                .thenReturn(CompletableFuture.failedFuture(new ConflictException("newer operation won")));
        Mockito.when(backend.start(starting)).thenReturn(completion);
        Assertions.assertThat(service.start(caller, startRequest()).revision()).isEqualTo(2L);
        completion.complete(null);
        Mockito.verify(store, Mockito.times(2)).update(Mockito.any(), Mockito.any());
    }

    @Test
    void synchronousBackendFailureRecordsMissingAgainstReservedRevision() {
        Instance starting = stopped.toBuilder().state(InstanceState.STARTING).revision(2L).build();
        Instance missing = starting.toBuilder().state(InstanceState.MISSING).build();
        Mockito.when(store.update(stopped, stopped.toBuilder().state(InstanceState.STARTING).build()))
                .thenReturn(CompletableFuture.completedFuture(starting));
        Mockito.when(store.update(starting, missing)).thenReturn(CompletableFuture.completedFuture(missing));
        Mockito.when(backend.start(starting)).thenThrow(new IllegalStateException("backend unavailable"));
        service.start(caller, startRequest());
        ArgumentCaptor<Instance> written = ArgumentCaptor.forClass(Instance.class);
        Mockito.verify(store).update(Mockito.eq(starting), written.capture());
        Assertions.assertThat(written.getValue().state()).isEqualTo(InstanceState.MISSING);
        Assertions.assertThat(written.getValue().revision()).isEqualTo(2L);
    }

    @Test
    void backendDeletionFailureIsReturnedAndRetainsRecord() {
        IllegalStateException failure = new IllegalStateException("delete failed");
        Mockito.when(backend.delete(stopped)).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.delete(caller, deleteRequest())).isSameAs(failure);
        Mockito.verify(store, Mockito.never()).delete(Mockito.any());
    }

    @Test
    void recordDeletionFailureIsReturnedAfterBackendSuccess() {
        Mockito.when(backend.delete(stopped)).thenReturn(CompletableFuture.completedFuture(null));
        ConflictException failure = new ConflictException("instance changed");
        Mockito.when(store.delete(stopped)).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.delete(caller, deleteRequest())).isSameAs(failure);
    }

    private StartInstanceRequest startRequest() {
        return StartInstanceRequest.builder().instanceId("i-1").build();
    }

    private DeleteInstanceRequest deleteRequest() {
        return DeleteInstanceRequest.builder().instanceId("i-1").build();
    }
}
