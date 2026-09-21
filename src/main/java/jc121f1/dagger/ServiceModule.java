package jc121f1.dagger;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import java.net.URI;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.SimpleEventBus;
import jc121f1.wbs.JmDNSManager;

import javax.inject.Singleton;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Module
public abstract class ServiceModule {

    @Provides @Singleton
    public static Clock clock() {
        return Clock.systemUTC();
    }

    @Binds @Singleton
    public abstract EventBus eventBus(SimpleEventBus eventBus);

    @Provides
    public static Executor executor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Provides @Singleton
    public static JmDNSManager jmDNSManager() {
        return new JmDNSManager();
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
