package jc121f1.wbs;

import io.javalin.Javalin;
import java.util.Objects;

public abstract class WebService implements AutoCloseable {

    private final JmDNSManager jmdnsManager;
    private Javalin app;
    private boolean closed;

    protected WebService(JmDNSManager jmdnsManager) {
        this.jmdnsManager = jmdnsManager;
    }

    abstract protected int getPort();

    abstract public Javalin create();

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Web service is closed");
        }
        if (app != null) {
            return;
        }
        try {
            app = Objects.requireNonNull(create(), "Web service app");
            app.start(getPort());
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Javalin running = app;
        app = null;
        if (running != null) {
            running.stop();
        }
    }

    public void startJmdns(String hostName, int port) {
        jmdnsManager.startMdns(hostName, port);
    }

    public void stopJmdns(String hostName) {
        jmdnsManager.stopMdns(hostName);
    }
}
