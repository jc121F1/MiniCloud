package jc121f1.service.instance;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.CreateInstanceRequest;
import jc121f1.model.instance.api.ListInstanceRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@MiniCloudTest
public class InstanceServiceTest {
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

        @Nested class When_receiving_a_valid_create_request {
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

        @Nested class When_receiving_a_valid_list_request {
            ListInstanceRequest request;
            List<Instance> response;

            @BeforeEach void setup() {
                request = new ListInstanceRequest();
            }

            @Nested class With_no_instances_stored {
                @BeforeEach void setup() {
                    response = instanceService.list(request);
                }

                @Test void It_should_return_an_empty_list() {
                    Assertions.assertThat(response).isEmpty();
                }
            }

            @Nested class With_one_instance_stored {
                Instance expected;

                @BeforeEach void setup() {
                    expected = instanceService.create(CreateInstanceRequest.builder()
                            .name(INSTANCE_NAME).cpu(DEFAULT_CPU).memory(DEFAULT_MEMORY).build());
                    response = instanceService.list();
                }

                @Test void It_should_return_a_list_of_one_instance_stored() {
                    Assertions.assertThat(response).hasSize(1);
                }

                @Test void Stored_instance_should_equal_expected() {
                    Assertions.assertThat(instanceService.list()).hasSize(1);
                    Assertions.assertThat(instanceService.list().getFirst()).isEqualTo(expected);
                }
            }
        }

        @Test void It_should_only_allow_one_instance_with_a_given_name_concurrently()
                throws InterruptedException {

            int threadCount = 50;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch start = new CountDownLatch(1);

            List<Future<Instance>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return instanceService.create(
                            CreateInstanceRequest.builder()
                                    .name(INSTANCE_NAME)
                                    .cpu(DEFAULT_CPU)
                                    .memory(DEFAULT_MEMORY)
                                    .build());
                }));
            }

            start.countDown();

            int successfulCreates = 0;

            for (Future<Instance> future : futures) {
                try {
                    future.get();
                    successfulCreates++;
                } catch (ExecutionException ignored) {
                    // Expected for duplicate creates.
                }
            }

            executor.shutdown();

            Assertions.assertThat(successfulCreates).isEqualTo(1);
            Assertions.assertThat(instanceService.list()).hasSize(1);
        }
    }
}
