package jc121f1.dagger;

import dagger.Module;
import dagger.Provides;
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
}
