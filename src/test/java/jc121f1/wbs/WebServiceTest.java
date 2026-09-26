package jc121f1.wbs;

import io.javalin.Javalin;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WebServiceTest {
    private final Javalin app = Mockito.mock(Javalin.class);
    private final JmDNSManager mdns = Mockito.mock(JmDNSManager.class);
    private final WebService service = Mockito.spy(new WebService(mdns) {
        @Override
        protected int getPort() {
            return 7070;
        }

        @Override
        public Javalin create() {
            return WebServiceTest.this.app;
        }
    });

    @Test
    void repeatedStartAndCloseOwnExactlyOneAppLifecycle() {
        service.start();
        service.start();
        service.close();
        service.close();
        Mockito.verify(service).create();
        Mockito.verify(app).start(7070);
        Mockito.verify(app).stop();
        Mockito.verifyNoInteractions(mdns);
        Assertions.assertThatThrownBy(service::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closingBeforeStartPreventsStartupWithoutCreatingAnApp() {
        service.close();
        Assertions.assertThatThrownBy(service::start).isInstanceOf(IllegalStateException.class);
        Mockito.verify(service, Mockito.never()).create();
        Mockito.verifyNoInteractions(app);
    }

    @Test
    void startupFailureStopsPartialAppAndPreventsRestart() {
        RuntimeException failure = new IllegalStateException("port unavailable");
        Mockito.when(app.start(7070)).thenThrow(failure);
        Assertions.assertThatThrownBy(service::start).isSameAs(failure);
        Mockito.verify(app).stop();
        Assertions.assertThatThrownBy(service::start).hasMessage("Web service is closed");
        service.close();
        Mockito.verify(app).stop();
    }

    @Test
    void cleanupFailureDoesNotHideStartupFailure() {
        RuntimeException failure = new IllegalStateException("start failed");
        RuntimeException cleanup = new IllegalStateException("stop failed");
        Mockito.when(app.start(7070)).thenThrow(failure);
        Mockito.doThrow(cleanup).when(app).stop();
        Assertions.assertThatThrownBy(service::start).isSameAs(failure).hasSuppressedException(cleanup);
    }

    @Test
    void failedCreationLeavesServiceClosed() {
        RuntimeException failure = new IllegalStateException("configuration failed");
        Mockito.doThrow(failure).when(service).create();
        Assertions.assertThatThrownBy(service::start).isSameAs(failure);
        Assertions.assertThatThrownBy(service::start).hasMessage("Web service is closed");
        Mockito.verifyNoInteractions(app);
    }

    @Test
    void failedStopIsReportedWithoutRepeatingTeardown() {
        service.start();
        RuntimeException failure = new IllegalStateException("stop failed");
        Mockito.doThrow(failure).when(app).stop();
        Assertions.assertThatThrownBy(service::close).isSameAs(failure);
        service.close();
        Mockito.verify(app).stop();
    }

    @Test
    void directCreateLeavesAppOwnershipWithCaller() {
        Assertions.assertThat(service.create()).isSameAs(app);
        service.close();
        Mockito.verifyNoInteractions(app);
    }
}
