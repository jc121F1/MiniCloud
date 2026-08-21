package jc121f1.dagger;

import dagger.Module;
import dagger.Provides;
import jc121f1.dagger.qualifiers.Debug;
import jc121f1.dagger.qualifiers.DisableJmDNS;
import jc121f1.dagger.qualifiers.ExposeShutdownEndpoint;
import jc121f1.dagger.qualifiers.RegistryMail;
import jc121f1.dagger.qualifiers.RegistryPass;
import jc121f1.dagger.qualifiers.RegistryUrl;
import jc121f1.dagger.qualifiers.RegistryUser;

import java.util.Optional;

@Module
public class EnvironmentModule {
    @Provides
    @RegistryUser
    String providerRegistryUser() {
        return Optional.ofNullable(System.getenv("DOCKER_USER")).orElse(System.getProperty("DOCKER_HOST"));
    }

    @Provides
    @RegistryPass
    String providerRegistryPass() {
        return Optional.ofNullable(System.getenv("DOCKER_PASS")).orElse(System.getProperty("DOCKER_PASS"));
    }

    @Provides
    @RegistryUrl
    String providerRegistryUrl() {
        return Optional.ofNullable(System.getenv("DOCKER_URL")).orElse(System.getProperty("DOCKER_URL"));
    }

    @Provides
    @RegistryMail
    String providerRegistryMail() {
        return Optional.ofNullable(System.getenv("DOCKER_MAIL")).orElse(System.getProperty("DOCKER_MAIL"));
    }

    @Provides
    @DisableJmDNS
    Boolean provideDisableJmDNS() {
        return getBooleanProperty("DISABLE_JMDNS");
    }

    @Provides
    @Debug
    Boolean provideDebug() {
        return getBooleanProperty("DEBUG_APP");
    }

    @Provides
    @ExposeShutdownEndpoint
    Boolean provideShutdownEndpoint() {
        return getBooleanProperty("SHUTDOWN_ENDPOINT");
    }

    private static boolean getBooleanProperty(String name) {
        return Boolean.parseBoolean(
                Optional.ofNullable(System.getenv(name))
                        .orElse(System.getProperty(name))
        );
    }
}
