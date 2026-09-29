package jc121f1.wbs;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;

class JmDNSManagerTest {
    private final JmDNSManager manager = Mockito.spy(new JmDNSManager());
    private final JmDNS first = Mockito.mock(JmDNS.class);
    private final JmDNS second = Mockito.mock(JmDNS.class);

    @AfterEach
    void closeManager() {
        manager.close();
    }

    @Test
    void duplicateRegistrationKeepsTheOriginalResponder() throws Exception {
        Mockito.doReturn(first).when(manager).createResponder("instance");
        manager.startMdns("instance", 7070);
        manager.startMdns("instance", 7071);
        Mockito.verify(manager).createResponder("instance");
        Mockito.verify(first).registerService(Mockito.any(ServiceInfo.class));
        manager.close();
        Mockito.verify(first).close();
    }

    @Test
    void stoppingRemovesTheEntryAndAllowsFreshRegistration() throws Exception {
        Mockito.doReturn(first, second).when(manager).createResponder("instance");
        manager.startMdns("instance", 7070);
        manager.stopMdns("instance");
        manager.stopMdns("instance");
        manager.startMdns("instance", 7070);
        Mockito.verify(first).close();
        Mockito.verify(second).registerService(Mockito.any(ServiceInfo.class));
        manager.close();
        Mockito.verify(first).close();
        Mockito.verify(second).close();
    }

    @Test
    void failedRegistrationClosesPartialResponderAndAllowsRetry() throws Exception {
        Mockito.doReturn(first, second).when(manager).createResponder("instance");
        Mockito.doThrow(new IOException("registration failed")).when(first)
                .registerService(Mockito.any(ServiceInfo.class));
        manager.startMdns("instance", 7070);
        Mockito.verify(first).close();
        manager.startMdns("instance", 7070);
        Mockito.verify(second).registerService(Mockito.any(ServiceInfo.class));
        manager.close();
        Mockito.verify(first).close();
        Mockito.verify(second).close();
    }

    @Test
    void closeAttemptsAllResourcesEvenWhenUnregisterAndCloseFail() throws Exception {
        Mockito.doReturn(first).when(manager).createResponder("instance");
        Mockito.doReturn(second).when(manager).createResponder("auth");
        manager.startMdns("instance", 7070);
        manager.startMdns("auth", 7071);
        Mockito.doThrow(new IllegalStateException("unregister failed")).when(first).unregisterAllServices();
        Mockito.doThrow(new IOException("close failed")).when(first).close();
        manager.close();
        manager.close();
        Mockito.verify(first).close();
        Mockito.verify(second).unregisterAllServices();
        Mockito.verify(second).close();
        Assertions.assertThatThrownBy(() -> manager.startMdns("instance", 7070))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void responderCreationFailureDoesNotPreventLaterRegistration() throws Exception {
        Mockito.doThrow(new IOException("no interface")).doReturn(second)
                .when(manager).createResponder("instance");
        manager.startMdns("instance", 7070);
        manager.startMdns("instance", 7070);
        Mockito.verify(second).registerService(Mockito.any(ServiceInfo.class));
    }
}
