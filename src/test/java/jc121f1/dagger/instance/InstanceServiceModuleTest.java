package jc121f1.dagger.instance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceServiceModuleTest {
    @Test
    void createsDockerClientWithoutRegistryCredentials() {
        InstanceWebServiceComponent component = DaggerInstanceWebServiceComponent.create();
        try (var resources = component.runtimeResources()) {
            assertThat(component.dockerClient()).isNotNull();
        }
    }
}
