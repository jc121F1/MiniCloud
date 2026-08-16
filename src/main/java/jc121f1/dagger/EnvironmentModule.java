package jc121f1.dagger;

import dagger.Module;
import dagger.Provides;
import jc121f1.dagger.qualifiers.RegistryMail;
import jc121f1.dagger.qualifiers.RegistryPass;
import jc121f1.dagger.qualifiers.RegistryUrl;
import jc121f1.dagger.qualifiers.RegistryUser;

@Module
public class EnvironmentModule {
    @Provides
    @RegistryUser
    String providerRegistryUser() {
        return System.getenv("DOCKER_USER");
    }

    @Provides
    @RegistryPass
    String providerRegistryPass() {
        return System.getenv("DOCKER_PASS");
    }

    @Provides
    @RegistryUrl
    String providerRegistryUrl() {
        return System.getenv("DOCKER_URL");
    }

    @Provides
    @RegistryMail
    String providerRegistryMail() {
        return System.getenv("DOCKER_MAIL");
    }
}
