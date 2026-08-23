package jc121f1.integration.testdagger;

import com.github.dockerjava.api.DockerClient;
import dagger.Module;
import dagger.Provides;
import jc121f1.services.instance.InstanceService;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.store.InstanceStore;
import org.assertj.core.util.VisibleForTesting;
import org.mockito.Mockito;

import javax.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@Module
@VisibleForTesting
public class TestServiceModule {

    @Provides
    @Singleton
    ComputeBackend computeBackend() {
        return Mockito.mock(ComputeBackend.class);
    }

    @Provides
    @Singleton
    DockerClient dockerClient() {
        return Mockito.mock(DockerClient.class);
    }

    @Provides
    @Singleton
    Clock clock() {
        return Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC
        );
    }

    @Provides
    @Singleton
    EventBus eventBus() {
        return Mockito.mock(EventBus.class);
    }

    @Provides
    @Singleton
    InstanceStore instanceStore() {
        return Mockito.mock(InstanceStore.class);
    }

    @Provides
    @Singleton
    InstanceService instanceService(
            Clock clock,
            ComputeBackend computeBackend,
            InstanceStore instanceStore) {

        return new InstanceServiceImpl(clock, computeBackend, eventBus(), instanceStore);
    }
}
