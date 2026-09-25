package jc121f1.service.instance;

import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.request.DeleteInstanceRequest;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.api.request.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.compute.docker.EventAction;
import jc121f1.services.instance.events.InstanceHealthEvent;
import jc121f1.services.instance.events.SimpleEventBus;
import jc121f1.services.instance.exceptions.ConflictException;
import jc121f1.services.instance.store.InstanceStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

class InstanceDeletionLifecycleTest {
    private static final AuthenticatedSession CALLER = new AuthenticatedSession("a-1", "u-1", Session.SubjectType.USER);
    private final InstanceStore store = Mockito.mock(InstanceStore.class);
    private final ComputeBackend backend = Mockito.mock(ComputeBackend.class);
    private final SimpleEventBus events = new SimpleEventBus(Runnable::run);
    private final AtomicReference<Instance> stored = new AtomicReference<>();
    private InstanceServiceImpl service;

    @BeforeEach
    void setup() {
        stored.set(Instance.builder().id("i-1").name("example").accountId("a-1")
                .state(InstanceState.STOPPED).revision(1L).build());
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of()));
        Mockito.when(store.get("i-1")).thenAnswer(call ->
                CompletableFuture.completedFuture(Optional.ofNullable(stored.get())));
        Mockito.when(store.update(Mockito.any(), Mockito.any())).thenAnswer(call -> {
            Instance previous = call.getArgument(0);
            Instance updated = call.getArgument(1);
            Instance versioned = updated.toBuilder().revision(previous.revision() + 1).build();
            return stored.compareAndSet(previous, versioned) ? CompletableFuture.completedFuture(versioned)
                    : CompletableFuture.failedFuture(new ConflictException("stale"));
        });
        Mockito.when(store.delete(Mockito.any())).thenAnswer(call -> stored.compareAndSet(call.getArgument(0), null)
                ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(new ConflictException("stale")));
        service = newService();
    }

    @Test
    void deleteReservesBeforeBackendAndPreventsStartStopAndHealthChanges() {
        Mockito.when(backend.delete(Mockito.any())).thenAnswer(call -> {
            Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.DELETING);
            Assertions.assertThat(stored.get().revision()).isEqualTo(2L);
            Assertions.assertThatThrownBy(this::start).isInstanceOf(ConflictException.class);
            Assertions.assertThatThrownBy(this::stop).isInstanceOf(ConflictException.class);
            publishHealthEvents();
            Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.DELETING);
            return CompletableFuture.completedFuture(null);
        });
        delete();
        Assertions.assertThat(stored.get()).isNull();
        Mockito.verify(backend, Mockito.never()).start(Mockito.any());
        Mockito.verify(backend, Mockito.never()).stop(Mockito.any());
    }

    @Test
    void pendingStartBlocksDeleteEvenAfterHealthEvents() {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Mockito.when(backend.start(Mockito.any())).thenReturn(started);
        start();
        publishHealthEvents();
        Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.STARTING);
        Assertions.assertThatThrownBy(this::delete).isInstanceOf(ConflictException.class);
        Mockito.verify(backend, Mockito.never()).delete(Mockito.any());
        started.complete(null);
        Mockito.when(backend.delete(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        delete();
        Assertions.assertThat(stored.get()).isNull();
    }

    @Test
    void pendingStopBlocksDelete() {
        stored.set(stored.get().toBuilder().state(InstanceState.RUNNING).build());
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        Mockito.when(backend.stop(Mockito.any())).thenReturn(stopped);
        stop();
        publishHealthEvents();
        Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.STOPPING);
        Assertions.assertThatThrownBy(this::delete).isInstanceOf(ConflictException.class);
        Mockito.verify(backend, Mockito.never()).delete(Mockito.any());
        stopped.complete(null);
        Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.STOPPED);
    }

    @Test
    void lostDeletionReservationDoesNotCallBackend() {
        Mockito.doReturn(CompletableFuture.failedFuture(new ConflictException("start won")))
                .when(store).update(Mockito.any(), Mockito.any());
        Assertions.assertThatThrownBy(this::delete).isInstanceOf(ConflictException.class);
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void backendFailureKeepsDeletionIntentAndRetryCompletes() {
        IllegalStateException failure = new IllegalStateException("backend unavailable");
        Mockito.when(backend.delete(Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure))
                .thenReturn(CompletableFuture.completedFuture(null));
        Assertions.assertThatThrownBy(this::delete).isSameAs(failure);
        Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.DELETING);
        Mockito.verify(store, Mockito.never()).delete(Mockito.any());
        delete();
        Assertions.assertThat(stored.get()).isNull();
        Mockito.verify(store, Mockito.times(1)).update(Mockito.any(), Mockito.any());
    }

    @Test
    void metadataFailureKeepsDeletionIntentAndRetryRepeatsIdempotentBackendDelete() {
        Mockito.when(backend.delete(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(store.delete(Mockito.any())).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("database unavailable")));
        Assertions.assertThatThrownBy(this::delete).hasMessage("database unavailable");
        Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.DELETING);
        Mockito.when(store.delete(Mockito.any())).thenAnswer(call -> {
            stored.set(null);
            return CompletableFuture.completedFuture(null);
        });
        delete();
        Mockito.verify(backend, Mockito.times(2)).delete(Mockito.any());
        Assertions.assertThat(stored.get()).isNull();
    }

    @Test
    void startupRecoversDeletingWithoutRecreatingBackend() {
        Instance deleting = stored.get().toBuilder().state(InstanceState.DELETING).build();
        stored.set(deleting);
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of(deleting)));
        Mockito.when(backend.describeStatuses(Mockito.any())).thenReturn(Map.of());
        Mockito.when(backend.delete(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        newService();
        Assertions.assertThat(stored.get()).isNull();
        Mockito.verify(backend, Mockito.never()).create(Mockito.any());
        Mockito.verify(backend, Mockito.never()).start(Mockito.any());
    }

    @Test
    void startupDeletionFailureRetainsReservation() {
        Instance deleting = stored.get().toBuilder().state(InstanceState.DELETING).build();
        stored.set(deleting);
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of(deleting)));
        Mockito.when(backend.describeStatuses(Mockito.any())).thenReturn(Map.of());
        Mockito.when(backend.delete(Mockito.any())).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("backend unavailable")));
        newService();
        Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.DELETING);
        Mockito.verify(store, Mockito.never()).update(Mockito.any(), Mockito.any());
    }

    @Test
    void startupReservesStableStateBeforeBackendRecovery() {
        Instance running = stored.get().toBuilder().state(InstanceState.RUNNING).build();
        stored.set(running);
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of(running)));
        Mockito.when(backend.describeStatuses(Mockito.any())).thenReturn(Map.of("i-1", ComputeStatus.STOPPED));
        Mockito.when(backend.start(Mockito.any())).thenAnswer(call -> {
            Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.STARTING);
            Assertions.assertThatThrownBy(this::delete).isInstanceOf(ConflictException.class);
            return CompletableFuture.completedFuture(null);
        });
        newService();
        Assertions.assertThat(stored.get().state()).isEqualTo(InstanceState.RUNNING);
        Mockito.verify(backend, Mockito.never()).delete(Mockito.any());
    }

    @Test
    void startupLosingStableStateReservationDoesNotTouchBackend() {
        Instance running = stored.get().toBuilder().state(InstanceState.RUNNING).build();
        stored.set(running);
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of(running)));
        Mockito.when(backend.describeStatuses(Mockito.any())).thenReturn(Map.of("i-1", ComputeStatus.STOPPED));
        Mockito.doReturn(CompletableFuture.failedFuture(new ConflictException("delete won")))
                .when(store).update(Mockito.any(), Mockito.any());
        newService();
        Mockito.verify(backend, Mockito.never()).create(Mockito.any());
        Mockito.verify(backend, Mockito.never()).start(Mockito.any());
        Mockito.verify(backend, Mockito.never()).stop(Mockito.any());
    }

    private InstanceServiceImpl newService() {
        return new InstanceServiceImpl(Clock.systemUTC(), backend, events, store, Mockito.mock(AuthorizationService.class));
    }

    private void publishHealthEvents() {
        events.publish(new InstanceHealthEvent("i-1", EventAction.UNHEALTHY));
        events.publish(new InstanceHealthEvent("i-1", EventAction.HEALTHY));
    }

    private void delete() {
        service.delete(CALLER, DeleteInstanceRequest.builder().instanceId("i-1").build());
    }

    private void start() {
        service.start(CALLER, StartInstanceRequest.builder().instanceId("i-1").build());
    }

    private void stop() {
        service.stop(CALLER, StopInstanceRequest.builder().instanceId("i-1").build());
    }
}
