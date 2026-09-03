package jc121f1.dagger;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.SimpleEventBus;
import jc121f1.wbs.JmDNSManager;

import javax.inject.Singleton;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Module
public abstract class ServiceModule {

    @Provides @Singleton
    public static Clock clock() {
        return Clock.systemUTC();
    }

    @Binds @Singleton
    public abstract EventBus eventBus(SimpleEventBus eventBus);

    @Provides
    public static Executor executor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Provides @Singleton
    public static JmDNSManager jmDNSManager() {
        return new JmDNSManager();
    }
}
