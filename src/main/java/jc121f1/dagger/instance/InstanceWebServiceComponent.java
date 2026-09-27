package jc121f1.dagger.instance;

import com.github.dockerjava.api.DockerClient;
import dagger.Component;
import jc121f1.dagger.EnvironmentModule;
import jc121f1.dagger.ServiceModule;
import jc121f1.dagger.WebServiceComponent;
import jc121f1.dagger.auth.AuthHandlers;
import jc121f1.dagger.auth.AuthServiceModule;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.store.InstanceStore;
import jc121f1.services.instance.store.nosql.DynamoDbInstanceStore;

import javax.inject.Singleton;

@Singleton
@Component(modules = {InstanceServiceModule.class, AuthServiceModule.class,
        ServiceModule.class, EnvironmentModule.class})
public interface InstanceWebServiceComponent extends WebServiceComponent, InstanceWebServiceHandlers, AuthHandlers {
    InstanceServiceImpl managedInstanceService();

    ComputeBackend computeBackend();

    DockerClient dockerClient();

    InstanceStore instanceStore();

    DynamoDbInstanceStore initializableInstanceStore();
}
