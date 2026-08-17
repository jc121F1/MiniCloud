package jc121f1.dagger;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
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

import javax.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Module
public class ServiceModule {

    @Provides @Singleton
    public InstanceService instanceService(Clock clock, ComputeBackend computeBackend) {
        return new InstanceServiceImpl(clock, computeBackend);
    }

    @Provides @Singleton
    public ComputeBackend computeBackend(DockerClient dockerClient, DockerEventListener eventListener) {
        return new DockerComputeBackend(dockerClient, eventListener);
    }

    @Provides @Singleton
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Provides @Singleton
    public DockerClient dockerClient(@RegistryUser String user,
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
    public DockerEventListener eventListener(DockerClient dockerClient) {
        return new DockerEventListener(dockerClient);
    }
}
