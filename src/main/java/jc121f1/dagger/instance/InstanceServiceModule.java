package jc121f1.dagger.instance;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import jc121f1.dagger.qualifiers.RegistryMail;
import jc121f1.dagger.qualifiers.RegistryPass;
import jc121f1.dagger.qualifiers.RegistryUrl;
import jc121f1.dagger.qualifiers.RegistryUser;
import jc121f1.services.instance.InstanceService;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.compute.docker.DockerComputeBackend;
import jc121f1.services.instance.compute.docker.DockerEventListener;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.store.InstanceStore;
import jc121f1.services.instance.store.nosql.DynamoDbInstanceStore;
import jc121f1.runtime.RuntimeResources;
import jc121f1.services.authz.AuthorizationService;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Module
public abstract class InstanceServiceModule {
    @Binds
    @Singleton
    public abstract InstanceService instanceService(InstanceServiceImpl instanceService);

    @Provides @Singleton
    public static InstanceServiceImpl managedInstanceService(Clock clock, ComputeBackend backend,
            EventBus events, InstanceStore store, AuthorizationService authorization, RuntimeResources resources) {
        return resources.own(new InstanceServiceImpl(clock, backend, events, store, authorization),
                RuntimeResources.Phase.BACKEND);
    }

    @Provides @Singleton
    public static ComputeBackend computeBackend(DockerComputeBackend backend, DockerClient client,
                                                 DockerEventListener listener, RuntimeResources resources) {
        return resources.ownComposite(backend, client, listener);
    }

    @Binds @Singleton
    public abstract InstanceStore instanceStore(DynamoDbInstanceStore instanceStore);

    @Provides
    @Singleton
    public static DockerClient dockerClient(@RegistryUser String user,
                                            @RegistryPass String pass,
                                            @RegistryMail String mail,
                                            @RegistryUrl String url,
                                            RuntimeResources resources) {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("npipe:////./pipe/dockerDesktopLinuxEngine")
                .withDockerTlsVerify(false)
                .withRegistryUsername(user)
                .withRegistryPassword(pass)
                .withRegistryEmail(mail)
                .withRegistryUrl(url)
                .build();

        ApacheDockerHttpClient client = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .responseTimeout(Duration.of(45, ChronoUnit.SECONDS))
                .build();

        DockerClient dockerClient;
        try {
            dockerClient = DockerClientImpl.getInstance(config, client);
        } catch (RuntimeException | Error failure) {
            try {
                client.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return resources.own(dockerClient, RuntimeResources.Phase.BACKEND);
    }

    @Provides @Singleton
    public static DockerEventListener eventListener(
            DockerClient dockerClient,
            EventBus eventBus,
            RuntimeResources resources
    ) {
        return resources.own(new DockerEventListener(dockerClient, eventBus), RuntimeResources.Phase.BACKEND);
    }

}
