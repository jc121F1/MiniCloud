package jc121f1.dagger.instance;

import com.github.dockerjava.api.DockerClient;
import dagger.Component;
import jc121f1.dagger.EnvironmentModule;
import jc121f1.dagger.ServiceModule;
import jc121f1.dagger.WebServiceComponent;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.store.InstanceStore;

import javax.inject.Singleton;

@Singleton
@Component(modules = {InstanceServiceModule.class, ServiceModule.class, EnvironmentModule.class})
public interface InstanceWebServiceComponent extends WebServiceComponent, InstanceWebServiceHandlers {
    ComputeBackend computeBackend();

    DockerClient dockerClient();

    InstanceStore instanceStore();
}
