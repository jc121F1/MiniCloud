package jc121f1.dagger;

import com.github.dockerjava.api.DockerClient;
import dagger.Component;
import jc121f1.dagger.qualifiers.Debug;
import jc121f1.dagger.qualifiers.DisableJmDNS;
import jc121f1.dagger.qualifiers.ExposeShutdownEndpoint;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.store.InstanceStore;
import jc121f1.wbs.exceptions.MiniCloudExceptionMapper;

import javax.inject.Singleton;

@Singleton
@Component(modules = {ServiceModule.class, EnvironmentModule.class})
public interface WebserviceComponent extends WebserviceHandlers {
    ComputeBackend computeBackend();

    DockerClient dockerClient();

    InstanceStore instanceStore();

    @ExposeShutdownEndpoint
    Boolean shutdownEndpoint();

    @Debug
    Boolean debug();

    @DisableJmDNS
    Boolean disableJmDNS();

    MiniCloudExceptionMapper exceptionMapper();
}
