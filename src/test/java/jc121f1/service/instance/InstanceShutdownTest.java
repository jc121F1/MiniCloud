package jc121f1.service.instance;

import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.request.CreateInstanceRequest;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.api.request.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.compute.docker.EventAction;
import jc121f1.services.instance.events.InstanceHealthEvent;
import jc121f1.services.instance.events.SimpleEventBus;
import jc121f1.services.instance.store.InstanceStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

class InstanceShutdownTest {
    private static final AuthenticatedSession CALLER = new AuthenticatedSession("a-1", "u-1",
            Session.SubjectType.USER);
    private final InstanceStore store = Mockito.mock(InstanceStore.class);
    private final ComputeBackend backend = Mockito.mock(ComputeBackend.class);
    private final SimpleEventBus events = new SimpleEventBus(Runnable::run);
    private final InstanceServiceImpl service = new InstanceServiceImpl(Clock.systemUTC(), backend, events, store,
            Mockito.mock(AuthorizationService.class));

    @BeforeEach
    void initialize() {
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of()));
        service.initialize();
    }

    @Test
    void closingDuringStartPreservesTheStartReservationForRecovery() {
        Instance stopped = instance(InstanceState.STOPPED);
        Instance starting = stopped.toBuilder().state(InstanceState.STARTING).revision(2L).build();
        Mockito.when(store.get("i-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(stopped)));
        Mockito.when(store.update(Mockito.any(), Mockito.any())).thenReturn(CompletableFuture.completedFuture(starting));
        CompletableFuture<Void> operation = new CompletableFuture<>();
        Mockito.when(backend.start(starting)).thenReturn(operation);
        service.start(CALLER, StartInstanceRequest.builder().instanceId("i-1").build());
        Mockito.clearInvocations(store);

        service.close();
        operation.completeExceptionally(new CancellationException("event stream closed"));
        Mockito.verify(store, Mockito.never()).update(Mockito.any(), Mockito.any());
        Assertions.assertThatThrownBy(() -> service.start(CALLER,
                StartInstanceRequest.builder().instanceId("i-1").build()))
                .hasMessage("Instance service is closed");
    }

    @Test
    void closingDuringStopPreservesTheStopReservationForRecovery() {
        Instance running = instance(InstanceState.RUNNING);
        Instance stopping = running.toBuilder().state(InstanceState.STOPPING).revision(2L).build();
        Mockito.when(store.get("i-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(running)));
        Mockito.when(store.update(Mockito.any(), Mockito.any())).thenReturn(CompletableFuture.completedFuture(stopping));
        CompletableFuture<Void> operation = new CompletableFuture<>();
        Mockito.when(backend.stop(stopping)).thenReturn(operation);
        service.stop(CALLER, StopInstanceRequest.builder().instanceId("i-1").build());
        Mockito.clearInvocations(store);

        service.close();
        operation.completeExceptionally(new CancellationException("event stream closed"));
        Mockito.verify(store, Mockito.never()).update(Mockito.any(), Mockito.any());
    }

    @Test
    void closingDuringCreateDoesNotRecordMissingOrStartTheContainer() {
        CompletableFuture<Void> operation = new CompletableFuture<>();
        Mockito.when(store.create(Mockito.any())).thenAnswer(call ->
                CompletableFuture.completedFuture(call.getArgument(0)));
        Mockito.when(backend.create(Mockito.any())).thenReturn(operation);
        service.create(CALLER, CreateInstanceRequest.builder().name("example").cpu(1).memory(8).build());
        Mockito.clearInvocations(store);

        service.close();
        operation.completeExceptionally(new CancellationException("backend closed"));
        Mockito.verify(store, Mockito.never()).update(Mockito.any(), Mockito.any());
        Mockito.verify(backend, Mockito.never()).start(Mockito.any());
    }

    @Test
    void healthReadFinishingAfterCloseCannotChangeState() {
        CompletableFuture<Optional<Instance>> read = new CompletableFuture<>();
        Mockito.when(store.get("i-1")).thenReturn(read);
        events.publish(new InstanceHealthEvent("i-1", EventAction.UNHEALTHY));
        service.close();
        read.complete(Optional.of(instance(InstanceState.RUNNING)));
        Mockito.verify(store, Mockito.never()).update(Mockito.any(), Mockito.any());
    }

    private Instance instance(InstanceState state) {
        return Instance.builder().id("i-1").name("example").accountId("a-1")
                .state(state).revision(1L).build();
    }
}
