package jc121f1.runtime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

class RuntimeResourcesTest {
    @Test
    void closesByPhaseRegardlessOfConstructionOrderAndContinuesAfterFailure() {
        List<String> closed = new ArrayList<>();
        RuntimeResources resources = new RuntimeResources();
        resources.own(() -> closed.add("client"), RuntimeResources.Phase.CLIENTS);
        resources.own(() -> closed.add("backend"), RuntimeResources.Phase.BACKEND);
        resources.own(() -> closed.add("worker"), RuntimeResources.Phase.WORKERS);
        resources.own(() -> {
            closed.add("failed backend");
            throw new IllegalStateException("close failed");
        }, RuntimeResources.Phase.BACKEND);
        resources.close();
        resources.close();
        Assertions.assertThat(closed).containsExactly("failed backend", "backend", "worker", "client");
    }

    @Test
    void compositeTakesOwnershipWithoutClosingChildrenTwice() throws Exception {
        RuntimeResources resources = new RuntimeResources();
        AutoCloseable child = Mockito.mock(AutoCloseable.class);
        resources.own(child, RuntimeResources.Phase.BACKEND);
        resources.ownComposite(child::close, child);
        resources.close();
        Mockito.verify(child).close();
    }

    @Test
    void failedConstructionLeavesChildrenOwnedAndLateRegistrationClosesImmediately() throws Exception {
        RuntimeResources resources = new RuntimeResources();
        AutoCloseable child = Mockito.mock(AutoCloseable.class);
        resources.own(child, RuntimeResources.Phase.BACKEND);
        resources.close();
        Mockito.verify(child).close();
        AutoCloseable late = Mockito.mock(AutoCloseable.class);
        Assertions.assertThatThrownBy(() -> resources.own(late, RuntimeResources.Phase.CLIENTS))
                .isInstanceOf(IllegalStateException.class);
        Mockito.verify(late).close();
    }

    @Test
    void executorDrainEscalatesAfterDeadlineBeforeClosingClients() throws Exception {
        RuntimeResources resources = new RuntimeResources();
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        AutoCloseable client = Mockito.mock(AutoCloseable.class);
        Mockito.when(executor.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(false, true);
        resources.own(client, RuntimeResources.Phase.CLIENTS);
        resources.ownExecutor(executor);
        resources.close();
        InOrder order = Mockito.inOrder(executor, client);
        order.verify(executor).shutdown();
        order.verify(executor).awaitTermination(5, TimeUnit.SECONDS);
        order.verify(executor).shutdownNow();
        order.verify(executor).awaitTermination(5, TimeUnit.SECONDS);
        order.verify(client).close();
        Mockito.verify(executor, Mockito.never()).close();
    }

    @Test
    void interruptedDrainPreservesInterruptAndStillClosesClients() throws Exception {
        RuntimeResources resources = new RuntimeResources();
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        AutoCloseable client = Mockito.mock(AutoCloseable.class);
        Mockito.when(executor.awaitTermination(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        resources.ownExecutor(executor);
        resources.own(client, RuntimeResources.Phase.CLIENTS);
        try {
            resources.close();
            Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
            Mockito.verify(executor).shutdownNow();
            Mockito.verify(client).close();
        } finally {
            Thread.interrupted();
        }
    }
}
