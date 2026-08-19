package jc121f1.dagger;

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
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.InstanceService;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.docker.DockerComputeBackend;
import jc121f1.services.instance.compute.docker.DockerEventListener;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.SimpleEventBus;

import javax.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Module
public abstract class ServiceModule {

    @Binds @Singleton
    public abstract InstanceService instanceService(InstanceServiceImpl  instanceService);

    @Binds @Singleton
    public abstract ComputeBackend computeBackend(DockerComputeBackend dockerComputeBackend);

    @Provides @Singleton
    public static Clock clock() {
        return Clock.systemUTC();
    }

    @Provides @Singleton
    public static DockerClient dockerClient(@RegistryUser String user,
                                     @RegistryPass String pass,
                                     @RegistryMail String mail,
                                     @RegistryUrl String url) {
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

        return DockerClientImpl.getInstance(config, client);
    }

    @Provides @Singleton
    public static DockerEventListener eventListener(DockerClient dockerClient) {
        return new DockerEventListener(dockerClient);
    }

    @Binds @Singleton
    public abstract EventBus eventBus(SimpleEventBus eventBus);

    @Provides
    public static Executor executor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
