package jc121f1.wbs;

import io.javalin.Javalin;
import jc121f1.wbs.exceptions.MiniCloudExceptionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WebServiceBootstrapTest {

    @Test
    void registersAndRemovesMdnsWithTheHttpServerLifecycle() {
        JmDNSManager mdns = Mockito.mock(JmDNSManager.class);
        Javalin app = WebServiceBootstrap.create(new WebServiceBootstrap.Options(
                "Test API", true, new MiniCloudExceptionMapper(), mdns, false, "test", 7079), config -> { });

        try {
            app.start(0);
        } finally {
            app.stop();
        }

        Mockito.verify(mdns).startMdns("test", 7079);
        Mockito.verify(mdns).stopMdns("test");
    }

    @Test
    void disabledMdnsDoesNotRequireAManager() {
        Javalin app = WebServiceBootstrap.create(new WebServiceBootstrap.Options(
                "Test API", false, new MiniCloudExceptionMapper(), null, true, "test", 7079), config -> { });

        try {
            app.start(0);
        } finally {
            app.stop();
        }

    }
}
