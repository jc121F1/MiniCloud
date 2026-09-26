package jc121f1.runtime;

import jc121f1.wbs.WebService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Stops every HTTP listener before releasing any component's dependencies. */
@Slf4j
public final class ApplicationRuntime implements AutoCloseable {
    private final List<WebService> services = new ArrayList<>();
    private final List<RuntimeResources> resources = new ArrayList<>();
    private boolean closed;

    public synchronized void startService(RuntimeResources owned, Supplier<WebService> factory) {
        if (closed) {
            owned.close();
            throw new IllegalStateException("Application runtime is closed");
        }
        resources.add(owned);
        try {
            WebService service = factory.get();
            services.add(service);
            service.start();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int index = services.size() - 1; index >= 0; index--) {
            try {
                services.get(index).close();
            } catch (Exception error) {
                log.warn("Unable to stop HTTP service", error);
            }
        }
        for (int index = resources.size() - 1; index >= 0; index--) {
            resources.get(index).close();
        }
        services.clear();
        resources.clear();
    }
}
