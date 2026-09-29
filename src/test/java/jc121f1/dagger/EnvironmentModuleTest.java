package jc121f1.dagger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentModuleTest {
    @Test
    void configuredDockerHostOverridesOsDefault() {
        assertThat(EnvironmentModule.resolveDockerHost("tcp://docker.example:2375", "Windows 11"))
                .isEqualTo("tcp://docker.example:2375");
    }

    @Test
    void windowsUsesDockerDesktopPipeByDefault() {
        assertThat(EnvironmentModule.resolveDockerHost(null, "Windows 11"))
                .isEqualTo("npipe:////./pipe/dockerDesktopLinuxEngine");
    }

    @Test
    void linuxUsesUnixSocketByDefault() {
        assertThat(EnvironmentModule.resolveDockerHost(null, "Linux"))
                .isEqualTo("unix:///var/run/docker.sock");
    }

    @Test
    void macOsUsesUnixSocketByDefault() {
        assertThat(EnvironmentModule.resolveDockerHost(null, "Mac OS X"))
                .isEqualTo("unix:///var/run/docker.sock");
    }
}
