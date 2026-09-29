package jc121f1.dagger;

import dagger.Module;
import dagger.Provides;
import jc121f1.dagger.qualifiers.Debug;
import jc121f1.dagger.qualifiers.DisableJmDNS;
import jc121f1.dagger.qualifiers.DockerHost;
import jc121f1.dagger.qualifiers.ExposeShutdownEndpoint;

import java.util.Locale;
import java.util.Optional;

@Module
public class EnvironmentModule {
    @Provides
    @DockerHost
    String provideDockerHost() {
        return resolveDockerHost(System.getenv("DOCKER_HOST"), System.getProperty("os.name"));
    }

    static String resolveDockerHost(String configuredHost, String osName) {
        if (configuredHost != null && !configuredHost.isBlank()) {
            return configuredHost;
        }
        return osName.toLowerCase(Locale.ROOT).startsWith("windows")
                ? "npipe:////./pipe/dockerDesktopLinuxEngine"
                : "unix:///var/run/docker.sock";
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
