package jc121f1.service.instance;

import jc121f1.model.instance.dao.Instance;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.model.instance.InstanceState;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.InstanceHealthEvent;
import jc121f1.services.instance.store.InstanceStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

class InstanceStartupTest {
    private final InstanceStore store = Mockito.mock(InstanceStore.class);
    private final ComputeBackend backend = Mockito.mock(ComputeBackend.class);
    private final EventBus events = Mockito.mock(EventBus.class);
    private final InstanceServiceImpl service = new InstanceServiceImpl(Clock.systemUTC(), backend, events, store,
            Mockito.mock(AuthorizationService.class));

    @Test
    void constructionHasNoIoOrSubscriptionsAndClosingPreventsInitialization() {
        Mockito.verifyNoInteractions(store, backend, events);
        service.close();
        Assertions.assertThatThrownBy(service::initialize).hasMessage("Instance service is closed");
        Mockito.verifyNoInteractions(store, backend, events);
    }

    @Test
    @SuppressWarnings("unchecked")
    void initializationAndCloseAreRepeatSafeAndUnsubscribeTheOriginalConsumer() {
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of()));
        service.initialize();
        service.initialize();
        service.close();
        service.close();
        ArgumentCaptor<Consumer<InstanceHealthEvent>> consumer = ArgumentCaptor.forClass(Consumer.class);
        Mockito.verify(events).subscribe(Mockito.eq(InstanceHealthEvent.class), consumer.capture());
        Mockito.verify(events).unsubscribe(InstanceHealthEvent.class, consumer.getValue());
        Mockito.verify(store).list();
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void initializationWaitsForTheStoreScanBeforeReturning() throws Exception {
        CompletableFuture<List<Instance>> rows = new CompletableFuture<>();
        CountDownLatch scanStarted = new CountDownLatch(1);
        Mockito.when(store.list()).thenAnswer(call -> {
            scanStarted.countDown();
            return rows;
        });
        CompletableFuture<Void> startup = CompletableFuture.runAsync(service::initialize);
        try {
            Assertions.assertThat(scanStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Assertions.assertThat(startup).isNotDone();
            rows.complete(List.of());
            startup.get(2, TimeUnit.SECONDS);
        } finally {
            rows.complete(List.of());
            service.close();
        }
    }

    @Test
    void restartRecoveryCompletesAnAlreadyRunningStartWithoutRecreatingItsContainer() {
        Instance starting = Instance.builder().id("i-1").name("example").accountId("a-1")
                .state(InstanceState.STARTING).revision(3L).build();
        Instance running = starting.toBuilder().state(InstanceState.RUNNING).build();
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of(starting)));
        Mockito.when(backend.describeStatuses(List.of(starting))).thenReturn(Map.of("i-1", ComputeStatus.RUNNING));
        Mockito.when(store.update(starting, running)).thenReturn(CompletableFuture.completedFuture(running));

        service.initialize();

        Mockito.verify(store).update(starting, running);
        Mockito.verify(backend, Mockito.never()).create(Mockito.any());
        Mockito.verify(backend, Mockito.never()).start(Mockito.any());
    }

    @Test
    void restartRecoveryDoesNotRecreateAContainerForAnInterruptedStop() {
        Instance stopping = Instance.builder().id("i-2").name("example").accountId("a-1")
                .state(InstanceState.STOPPING).revision(4L).build();
        Instance missing = stopping.toBuilder().state(InstanceState.MISSING).build();
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of(stopping)));
        Mockito.when(backend.describeStatuses(List.of(stopping))).thenReturn(Map.of("i-2", ComputeStatus.MISSING));
        Mockito.when(store.update(stopping, missing)).thenReturn(CompletableFuture.completedFuture(missing));

        service.initialize();

        Mockito.verify(store).update(stopping, missing);
        Mockito.verify(backend, Mockito.never()).create(Mockito.any());
        Mockito.verify(backend, Mockito.never()).stop(Mockito.any());
    }

    @Test
    void startupDeadlinePreservesAnUnresolvedTransitionAndClosesTheService() {
        Instance starting = Instance.builder().id("i-3").name("example").accountId("a-1")
                .state(InstanceState.STARTING).revision(5L).build();
        Instance later = Instance.builder().id("i-4").name("later").accountId("a-1")
                .state(InstanceState.STARTING).revision(6L).build();
        CompletableFuture<Void> start = new CompletableFuture<>();
        Mockito.when(store.list()).thenReturn(CompletableFuture.completedFuture(List.of(starting, later)));
        Mockito.when(backend.describeStatuses(List.of(starting, later))).thenReturn(Map.of(
                "i-3", ComputeStatus.STOPPED, "i-4", ComputeStatus.STOPPED));
        Mockito.when(backend.start(starting)).thenReturn(start);
        InstanceServiceImpl boundedService = new InstanceServiceImpl(Clock.systemUTC(), backend, events, store,
                Mockito.mock(AuthorizationService.class), Duration.ofMillis(100));

        Assertions.assertThatThrownBy(boundedService::initialize)
                .hasRootCauseInstanceOf(TimeoutException.class);
        start.complete(null);

        Mockito.verify(store, Mockito.never()).update(Mockito.any(), Mockito.any());
        Mockito.verify(backend, Mockito.never()).create(Mockito.any());
        Mockito.verify(backend, Mockito.never()).start(later);
        Assertions.assertThatThrownBy(() -> boundedService.initialize())
                .hasMessage("Instance service is closed");
    }

    @Test
    void asynchronousStartupFailurePropagatesAndRemovesSubscription() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        Mockito.when(store.list()).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(service::initialize).isSameAs(failure);
        Mockito.verify(events).unsubscribe(Mockito.eq(InstanceHealthEvent.class), Mockito.any());
        Assertions.assertThatThrownBy(service::initialize).hasMessage("Instance service is closed");
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void synchronousStartupFailurePreservesOriginalErrorWhenCleanupFails() {
        IllegalStateException failure = new IllegalStateException("scan failed");
        IllegalStateException cleanup = new IllegalStateException("unsubscribe failed");
        Mockito.when(store.list()).thenThrow(failure);
        Mockito.doThrow(cleanup).when(events).unsubscribe(Mockito.eq(InstanceHealthEvent.class), Mockito.any());
        Assertions.assertThatThrownBy(service::initialize).isSameAs(failure).hasSuppressedException(cleanup);
    }
}
