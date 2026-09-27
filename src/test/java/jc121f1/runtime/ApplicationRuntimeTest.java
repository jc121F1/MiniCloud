package jc121f1.runtime;

import jc121f1.wbs.WebService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class ApplicationRuntimeTest {
    @Test
    void initializationFinishesBeforeOpeningHttp() {
        ApplicationRuntime runtime = new ApplicationRuntime();
        RuntimeResources resources = Mockito.mock(RuntimeResources.class);
        Runnable initialize = Mockito.mock(Runnable.class);
        WebService service = Mockito.mock(WebService.class);
        runtime.startService(resources, initialize, () -> service);
        InOrder order = Mockito.inOrder(initialize, service);
        order.verify(initialize).run();
        order.verify(service).start();
        runtime.close();
    }

    @Test
    void initializationFailureClosesResourcesWithoutCreatingHttpService() {
        ApplicationRuntime runtime = new ApplicationRuntime();
        RuntimeResources resources = Mockito.mock(RuntimeResources.class);
        IllegalStateException failure = new IllegalStateException("reconciliation failed");
        Assertions.assertThatThrownBy(() -> runtime.startService(resources, () -> {
            throw failure;
        }, () -> {
            throw new AssertionError("HTTP service must not be created after initialization failed");
        })).isSameAs(failure);
        Mockito.verify(resources).close();
    }

    @Test
    void allHttpServicesStopBeforeAnyDependenciesEvenIfOneStopFails() {
        ApplicationRuntime runtime = new ApplicationRuntime();
        WebService first = Mockito.mock(WebService.class);
        WebService second = Mockito.mock(WebService.class);
        RuntimeResources firstResources = Mockito.mock(RuntimeResources.class);
        RuntimeResources secondResources = Mockito.mock(RuntimeResources.class);
        runtime.startService(firstResources, () -> first);
        runtime.startService(secondResources, () -> second);
        Mockito.doThrow(new IllegalStateException("stop failed")).when(second).close();
        runtime.close();
        runtime.close();
        InOrder order = Mockito.inOrder(first, second, firstResources, secondResources);
        order.verify(second).close();
        order.verify(first).close();
        order.verify(secondResources).close();
        order.verify(firstResources).close();
        Mockito.verify(first).close();
        Mockito.verify(secondResources).close();
    }

    @Test
    void failedServiceStartClosesItAndPreviouslyStartedServicesAndTheirDependencies() {
        ApplicationRuntime runtime = new ApplicationRuntime();
        WebService first = Mockito.mock(WebService.class);
        WebService failed = Mockito.mock(WebService.class);
        RuntimeResources firstResources = Mockito.mock(RuntimeResources.class);
        RuntimeResources failedResources = Mockito.mock(RuntimeResources.class);
        IllegalStateException failure = new IllegalStateException("port unavailable");
        Mockito.doThrow(failure).when(failed).start();
        runtime.startService(firstResources, () -> first);
        Assertions.assertThatThrownBy(() -> runtime.startService(failedResources, () -> failed)).isSameAs(failure);
        InOrder order = Mockito.inOrder(first, failed, firstResources, failedResources);
        order.verify(failed).close();
        order.verify(first).close();
        order.verify(failedResources).close();
        order.verify(firstResources).close();
    }

    @Test
    void factoryFailureClosesResourcesCreatedBeforeServiceExists() {
        ApplicationRuntime runtime = new ApplicationRuntime();
        RuntimeResources resources = new RuntimeResources();
        AutoCloseable client = Mockito.mock(AutoCloseable.class);
        IllegalStateException failure = new IllegalStateException("store initialization failed");
        Assertions.assertThatThrownBy(() -> runtime.startService(resources, () -> {
            resources.own(client, RuntimeResources.Phase.CLIENTS);
            throw failure;
        })).isSameAs(failure);
        Assertions.assertThatCode(() -> Mockito.verify(client).close()).doesNotThrowAnyException();
    }
}
