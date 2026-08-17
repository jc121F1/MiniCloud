package jc121f1.service.instance;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.CreateInstanceRequest;
import jc121f1.model.instance.api.DeleteInstanceRequest;
import jc121f1.model.instance.api.GetInstanceRequest;
import jc121f1.model.instance.api.ListInstanceRequest;
import jc121f1.model.instance.api.StartInstanceRequest;
import jc121f1.model.instance.api.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
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
import java.util.concurrent.CompletableFuture;
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
    @Mock private ComputeBackend computeBackend;

    @Nested class Given_an_instance_service {
        InstanceService instanceService;

        @BeforeEach
        void setup() {
            instanceService = new InstanceServiceImpl(clock, computeBackend);
            Mockito.lenient().when(computeBackend.start(Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            Mockito.lenient().when(computeBackend.create(Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            Mockito.lenient().when(computeBackend.stop(Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
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
                Assertions.assertThat(instanceService.list().getFirst()).isEqualTo(instance);
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
                    Assertions.assertThat(instanceService.list()).hasSize(1);
                    Assertions.assertThat(instanceService.list().getFirst()).isEqualTo(instance);
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

        @Nested
        class When_receiving_a_valid_get_request {
            Instance expected;

            @BeforeEach
            void setup() {
                expected = instanceService.create(CreateInstanceRequest.builder()
                        .name(INSTANCE_NAME)
                        .cpu(DEFAULT_CPU)
                        .memory(DEFAULT_MEMORY)
                        .build());
            }

            @Nested
            class With_instance_id {
                Instance response;

                @BeforeEach
                void setup() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .instanceId(expected.getId())
                            .build();

                    response = instanceService.get(request);
                }

                @Test
                void It_should_return_the_instance() {
                    Assertions.assertThat(response).isEqualTo(expected);
                }
            }

            @Nested
            class With_instance_name {
                Instance response;

                @BeforeEach
                void setup() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .name(expected.getName())
                            .build();

                    response = instanceService.get(request);
                }

                @Test
                void It_should_return_the_instance() {
                    Assertions.assertThat(response).isEqualTo(expected);
                }
            }

            @Nested
            class With_unknown_identifier {
                @Test
                void It_should_throw_exception() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .instanceId("i-does-not-exist")
                            .build();

                    Assertions.assertThatThrownBy(() -> instanceService.get(request))
                            .hasMessage("Resource not found");
                }
            }
        }

        @Test
        void Get_should_require_an_identifier() {
            GetInstanceRequest request = GetInstanceRequest.builder()
                    .build();

            Assertions.assertThatThrownBy(() -> instanceService.get(request))
                    .hasMessageContaining("name")
                    .hasMessageContaining("instanceId");
        }

        @Nested
        class When_receiving_a_valid_delete_request {
            Instance expected;
            Instance response;

            @BeforeEach
            void setup() {
                expected = instanceService.create(CreateInstanceRequest.builder()
                        .name(INSTANCE_NAME)
                        .cpu(DEFAULT_CPU)
                        .memory(DEFAULT_MEMORY)
                        .build());
            }

            @Nested
            class With_instance_id {
                @BeforeEach
                void setup() {
                    DeleteInstanceRequest request = DeleteInstanceRequest.builder()
                            .instanceId(expected.getId())
                            .build();

                    response = instanceService.delete(request);
                }

                @Test
                void It_should_return_the_deleted_instance() {
                    Assertions.assertThat(response).isEqualTo(expected);
                }

                @Test
                void Instance_should_no_longer_be_listable() {
                    Assertions.assertThat(instanceService.list()).isEmpty();
                }

                @Test
                void Instance_should_no_longer_be_gettable_by_id() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .instanceId(expected.getId())
                            .build();

                    Assertions.assertThatThrownBy(() -> instanceService.get(request))
                            .hasMessage("Resource not found");
                }

                @Test
                void Instance_should_no_longer_be_gettable_by_name() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .name(expected.getName())
                            .build();

                    Assertions.assertThatThrownBy(() -> instanceService.get(request))
                            .hasMessage("Resource not found");
                }
            }

            @Nested
            class With_instance_name {
                @BeforeEach
                void setup() {
                    DeleteInstanceRequest request = DeleteInstanceRequest.builder()
                            .name(expected.getName())
                            .build();

                    response = instanceService.delete(request);
                }

                @Test
                void It_should_return_the_deleted_instance() {
                    Assertions.assertThat(response).isEqualTo(expected);
                }

                @Test
                void Instance_should_no_longer_be_listable() {
                    Assertions.assertThat(instanceService.list()).isEmpty();
                }

                @Test
                void Instance_should_no_longer_be_gettable_by_id() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .instanceId(expected.getId())
                            .build();

                    Assertions.assertThatThrownBy(() -> instanceService.get(request))
                            .hasMessage("Resource not found");
                }

                @Test
                void Instance_should_no_longer_be_gettable_by_name() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .name(expected.getName())
                            .build();

                    Assertions.assertThatThrownBy(() -> instanceService.get(request))
                            .hasMessage("Resource not found");
                }
            }
        }

        @Test
        void Delete_should_require_an_identifier() {
            DeleteInstanceRequest request = DeleteInstanceRequest.builder()
                    .build();

            Assertions.assertThatThrownBy(() -> instanceService.delete(request))
                    .hasMessageContaining("name")
                    .hasMessageContaining("instanceId");
        }

        @Test
        void Delete_should_throw_for_unknown_instance() {
            DeleteInstanceRequest request = DeleteInstanceRequest.builder()
                    .instanceId("i-does-not-exist")
                    .build();

            Assertions.assertThatThrownBy(() -> instanceService.delete(request))
                    .hasMessage("Resource not found");
        }

        @Nested
        class When_starting_an_instance {
            Instance instance;

            @BeforeEach
            void setup() {
                instance = instanceService.create(CreateInstanceRequest.builder()
                        .name(INSTANCE_NAME)
                        .cpu(DEFAULT_CPU)
                        .memory(DEFAULT_MEMORY)
                        .build());

                instanceService.stop(StopInstanceRequest.builder().instanceId(instance.getId()).build());
            }

            @Test
            void It_should_throw_for_unknown_instance() {
                StartInstanceRequest request = StartInstanceRequest.builder()
                        .instanceId("i-does-not-exist")
                        .build();

                Assertions.assertThatThrownBy(() -> instanceService.start(request))
                        .hasMessage("Resource not found");
            }

            @Test
            void It_should_set_state_to_starting() {
                StartInstanceRequest request = StartInstanceRequest.builder()
                        .instanceId(instance.getId())
                        .build();

                Instance response = instanceService.start(request);

                Assertions.assertThat(response).isEqualTo(instance);
                Assertions.assertThat(response.getState()).isEqualTo(InstanceState.STARTING);
            }

            @Test
            void It_should_support_lookup_by_name() {
                StartInstanceRequest request = StartInstanceRequest.builder()
                        .name(instance.getName())
                        .build();

                Instance response = instanceService.start(request);

                Assertions.assertThat(response.getState()).isEqualTo(InstanceState.STARTING);
            }

            @Test
            void It_should_reject_an_instance_that_cannot_be_started() {
                instance = instanceService.create(CreateInstanceRequest.builder()
                        .name(INSTANCE_NAME + "a")
                        .cpu(DEFAULT_CPU)
                        .memory(DEFAULT_MEMORY)
                        .build());

                StartInstanceRequest request = StartInstanceRequest.builder()
                        .instanceId(instance.getId())
                        .build();

                Assertions.assertThatThrownBy(() -> instanceService.start(request))
                        .hasMessageContaining("not in a startable state");
            }

            @Test
            void It_should_require_an_identifier() {
                StartInstanceRequest request = StartInstanceRequest.builder()
                        .build();

                Assertions.assertThatThrownBy(() -> instanceService.start(request))
                        .hasMessageContaining("name")
                        .hasMessageContaining("instanceId");
            }
        }

        @Nested
        class When_stopping_an_instance {
            Instance instance;

            @BeforeEach
            void setup() {
                instance = instanceService.create(CreateInstanceRequest.builder()
                        .name(INSTANCE_NAME)
                        .cpu(DEFAULT_CPU)
                        .memory(DEFAULT_MEMORY)
                        .build());

                instance.setState(InstanceState.RUNNING);
            }

            @Test
            void It_should_set_state_to_stopping() {
                StopInstanceRequest request = StopInstanceRequest.builder()
                        .instanceId(instance.getId())
                        .build();

                Instance response = instanceService.stop(request);

                Assertions.assertThat(response).isEqualTo(instance);
                Assertions.assertThat(response.getState()).isEqualTo(InstanceState.STOPPING);
            }

            @Test
            void It_should_support_lookup_by_name() {
                StopInstanceRequest request = StopInstanceRequest.builder()
                        .name(instance.getName())
                        .build();

                Instance response = instanceService.stop(request);

                Assertions.assertThat(response.getState()).isEqualTo(InstanceState.STOPPING);
            }

            @Test
            void It_should_reject_an_instance_that_cannot_be_stopped() {
                StopInstanceRequest request = StopInstanceRequest.builder()
                        .instanceId(instance.getId())
                        .build();

                instanceService.stop(request);

                Assertions.assertThatThrownBy(() -> instanceService.stop(request))
                        .hasMessageContaining("not in a startable state");
            }

            @Test
            void It_should_require_an_identifier() {
                StopInstanceRequest request = StopInstanceRequest.builder()
                        .build();

                Assertions.assertThatThrownBy(() -> instanceService.stop(request))
                        .hasMessageContaining("name")
                        .hasMessageContaining("instanceId");
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
