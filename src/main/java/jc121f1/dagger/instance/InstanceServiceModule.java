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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import javax.inject.Singleton;
import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Module
public abstract class InstanceServiceModule {
    @Binds
    @Singleton
    public abstract InstanceService instanceService(InstanceServiceImpl instanceService);

    @Binds @Singleton
    public abstract ComputeBackend computeBackend(DockerComputeBackend dockerComputeBackend);

    @Binds @Singleton
    public abstract InstanceStore instanceStore(DynamoDbInstanceStore instanceStore);

    @Provides
    @Singleton
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
    public static DockerEventListener eventListener(
            DockerClient dockerClient,
            EventBus eventBus
    ) {
        return new DockerEventListener(dockerClient, eventBus);
    }

    @Provides
    public static DynamoDbAsyncClient dynamoDbAsyncClient() {
        return DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create("http://localhost:8000"))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create("dummy", "dummy")
                        )
                )
                .build();
    }
}
