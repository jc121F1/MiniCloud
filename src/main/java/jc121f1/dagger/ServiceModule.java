package jc121f1.dagger;

import dagger.Module;
import dagger.Provides;
import jc121f1.services.instance.InstanceService;
import jc121f1.services.instance.InstanceServiceImpl;

import java.time.Clock;

@Module
public class ServiceModule {

    @Provides public InstanceService instanceService() {
        return new InstanceServiceImpl(Clock.systemUTC());
    }
}
