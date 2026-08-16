package jc121f1.dagger;

import dagger.Module;
import dagger.Provides;
import jc121f1.services.instance.InstanceService;
import jc121f1.services.instance.InstanceServiceImpl;

import javax.inject.Singleton;
import java.time.Clock;

@Module
public class ServiceModule {

    @Provides @Singleton
    public InstanceService instanceService() {
        return new InstanceServiceImpl(Clock.systemUTC());
    }
}
