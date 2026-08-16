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
import jc121f1.services.instance.InstanceService;
import jc121f1.services.instance.InstanceServiceImpl;

import javax.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Module
public class ServiceModule {

    @Provides @Singleton
    public InstanceService instanceService() {
        return new InstanceServiceImpl(Clock.systemUTC());

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
                .withDockerHost("tcp://localhost:2375")
                .withDockerTlsVerify(true)
                .withDockerCertPath("/home/user/.docker")
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
}
