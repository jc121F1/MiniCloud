package jc121f1.wbs;

import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class WebService {

    private final JmDNSManager jmdnsManager;
    private Javalin app;

    protected WebService(JmDNSManager jmdnsManager) {
        this.jmdnsManager = jmdnsManager;
    }

    abstract protected int getPort();

    abstract public Javalin create();

    public void start() {
        app = create();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        app.start(getPort());
    }

    public void startJmdns(String hostName, int port) {
        jmdnsManager.startMdns(hostName, port);
    }

    public void stopJmdns(String hostName) {
        jmdnsManager.stopMdns(hostName);
    }
}
