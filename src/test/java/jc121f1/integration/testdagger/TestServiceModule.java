package jc121f1.integration.testdagger;

import dagger.Module;
import dagger.Provides;
import jc121f1.services.instance.InstanceService;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.ComputeBackend;
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
    Clock clock() {
        return Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC
        );
    }

    @Provides
    @Singleton
    InstanceService instanceService(
            Clock clock,
            ComputeBackend computeBackend) {

        return new InstanceServiceImpl(clock, computeBackend);
    }
}
