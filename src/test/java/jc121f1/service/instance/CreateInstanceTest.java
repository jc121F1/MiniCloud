package jc121f1.service.instance;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.CreateInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
import jc121f1.services.instance.InstanceServiceImpl;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@MiniCloudTest
public class CreateInstanceTest {
    private static final String INSTANCE_NAME = "INSTANCE_NAME";
    private static final int DEFAULT_CPU = 1;
    private static final int DEFAULT_MEMORY = 1;

    @Mock private Clock clock;

    @Nested class Given_an_instance_service {
        InstanceService instanceService;

        @BeforeEach
        void setup() {
            instanceService = new InstanceServiceImpl(clock);
        }

        @Nested
        class With_valid_create_request {
            CreateInstanceRequest request;
            Instance instance;
            Instant createdAt;

            @BeforeEach void setup() {
                request = CreateInstanceRequest.builder()
                        .name(INSTANCE_NAME).cpu(DEFAULT_CPU).memory(DEFAULT_MEMORY)
                        .build();
                createdAt = Instant.parse("2026-01-01T00:00:00Z");
                Mockito.when(clock.instant()).thenReturn(createdAt);
                instance = instanceService.create(request);
            }

            @Test void It_should_return_an_instance() {
                Assertions.assertThat(instance).isNotNull();
            }

            @Test void It_should_have_the_requested_name() {
                Assertions.assertThat(instance.getName()).isEqualTo(INSTANCE_NAME);
            }

            @Test void It_should_have_the_requested_cpu() {
                Assertions.assertThat(instance.getCpu()).isEqualTo(DEFAULT_CPU);
            }

            @Test void It_should_have_the_requested_memory() {
                Assertions.assertThat(instance.getMemory()).isEqualTo(DEFAULT_MEMORY);
            }

            @Test void It_should_have_state_starting() {
                Assertions.assertThat(instance.getState()).isEqualTo(InstanceState.STARTING);
            }

            @Test void It_should_return_an_instance_id() {
                Assertions.assertThat(instance.getId()).isNotNull();
                Assertions.assertThat(instance.getId()).startsWith("i-");
            }

            @Test void It_should_have_creation_time() {
                Assertions.assertThat(instance.getCreatedAt()).isEqualTo(createdAt);
            }

            @Test void After_two_seconds_it_should_be_running() throws InterruptedException {
                TimeUnit.SECONDS.sleep(3);
                Assertions.assertThat(instance.getState()).isEqualTo(InstanceState.RUNNING);
            }

            @Test void Instance_should_be_listable() {
                Assertions.assertThat(instanceService.list()).containsExactly(instance);
            }
        }

        @Nested class When_creating_two_instances {
            @Nested class With_same_instance_name {
                CreateInstanceRequest request;
                Instance instance;

                @BeforeEach void setup() {
                    request = CreateInstanceRequest.builder()
                            .name(INSTANCE_NAME).cpu(DEFAULT_CPU).memory(DEFAULT_MEMORY)
                            .build();

                    Mockito.when(clock.instant()).thenReturn(Instant.now());
                    instance = instanceService.create(request);
                }

                @Test void It_should_throw_exception() {
                    Assertions.assertThatThrownBy(() -> instanceService.create(request)).hasMessageContainingAll(
                            INSTANCE_NAME, "already exists"
                    );
                }

                @Test void It_should_only_create_one_instance() {
                    Assertions.assertThat(instanceService.list()).containsExactly(instance);
                }
            }

            @Nested class With_valid_requests {
                CreateInstanceRequest request1;
                CreateInstanceRequest request2;
                Instance instance1;
                Instance instance2;

                @BeforeEach void setup() {
                    request1 = CreateInstanceRequest.builder()
                            .name(INSTANCE_NAME + "1").cpu(DEFAULT_CPU).memory(DEFAULT_MEMORY)
                            .build();

                    request2 = CreateInstanceRequest.builder()
                            .name(INSTANCE_NAME + "2").cpu(DEFAULT_CPU).memory(DEFAULT_MEMORY)
                            .build();

                    Mockito.when(clock.instant()).thenReturn(Instant.now());
                    instance1 = instanceService.create(request1);
                    instance2 = instanceService.create(request2);
                }

                @Test void It_should_create_both_instances() {
                    Assertions.assertThat(instance1).isNotNull();
                    Assertions.assertThat(instance2).isNotNull();
                }

                @Test void Instances_should_have_different_ids() {
                    Assertions.assertThat(instance1.getId()).isNotEqualTo(instance2.getId());
                }
            }
        }
    }
}
