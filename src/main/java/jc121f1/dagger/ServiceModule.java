package jc121f1.dagger;

import dagger.Module;
import dagger.Provides;
import jc121f1.services.instance.InstanceService;

@Module
public class ServiceModule {

    @Provides public InstanceService instanceService() {
        return new InstanceService();
    }
}
