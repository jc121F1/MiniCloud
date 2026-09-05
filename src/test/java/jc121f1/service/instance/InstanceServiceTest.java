package jc121f1.service.instance;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.request.CreateInstanceRequest;
import jc121f1.model.instance.api.request.DeleteInstanceRequest;
import jc121f1.model.instance.api.request.GetInstanceRequest;
import jc121f1.model.instance.api.request.ListInstanceRequest;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.api.request.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.InstanceService;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.docker.EventAction;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.events.InstanceHealthEvent;
import jc121f1.services.instance.events.SimpleEventBus;
import jc121f1.services.instance.store.InstanceStore;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentMap;

@MiniCloudTest
public class InstanceServiceTest {
    private static final String INSTANCE_NAME = "INSTANCE_NAME";
    private static final int DEFAULT_CPU = 1;
    private static final int DEFAULT_MEMORY = 1;

    @Mock private Clock clock;
    @Mock private ComputeBackend computeBackend;
    @Mock private InstanceStore instanceStore;
    @Spy private EventBus eventBus = new SimpleEventBus(Executors.newVirtualThreadPerTaskExecutor());

    @Nested class Given_an_instance_service {
        InstanceService instanceService;
        ConcurrentMap<String, Instance> instancesById;
        ConcurrentMap<String, String> idsByName;

        @BeforeEach
        void setup() {
            instancesById = new ConcurrentHashMap<>();
            idsByName = new ConcurrentHashMap<>();
            Mockito.lenient().when(computeBackend.start(Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            Mockito.lenient().when(computeBackend.create(Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            Mockito.lenient().when(computeBackend.stop(Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            Mockito.lenient().when(computeBackend.delete(Mockito.any()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            Mockito.lenient().when(computeBackend.describeStatuses(Mockito.anyList()))
                    .thenReturn(Map.of());

            Mockito.lenient().when(instanceStore.get(Mockito.anyString()))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(
                            java.util.Optional.ofNullable(instancesById.get(invocation.getArgument(0)))
                    ));
            Mockito.lenient().when(instanceStore.getByName(Mockito.anyString()))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(
                            java.util.Optional.ofNullable(idsByName.get(invocation.getArgument(0)))
                                    .map(instancesById::get)
                    ));
            Mockito.lenient().when(instanceStore.list())
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(
                            List.copyOf(instancesById.values())
                    ));
            Mockito.lenient().when(instanceStore.create(Mockito.any()))
                    .thenAnswer(invocation -> {
                        Instance created = invocation.getArgument(0);
                        if (idsByName.putIfAbsent(created.name(), created.id()) != null) {
                            return CompletableFuture.failedFuture(new IllegalArgumentException(
                                    "An instance with name '" + created.name() + "' already exists"
                            ));
                        }
                        instancesById.put(created.id(), created);
                        return CompletableFuture.completedFuture(created);
                    });
            Mockito.lenient().when(instanceStore.update(Mockito.any(), Mockito.any()))
                    .thenAnswer(invocation -> {
                        Instance previous = invocation.getArgument(0);
                        Instance updated = invocation.getArgument(1);
                        instancesById.replace(previous.id(), updated);
                        if (!previous.name().equals(updated.name())) {
                            idsByName.remove(previous.name(), previous.id());
                            idsByName.put(updated.name(), updated.id());
                        }
                        return CompletableFuture.completedFuture(updated);
                    });
            Mockito.lenient().when(instanceStore.delete(Mockito.any()))
                    .thenAnswer(invocation -> {
                        Instance deleted = invocation.getArgument(0);
                        instancesById.remove(deleted.id());
                        idsByName.remove(deleted.name(), deleted.id());
                        return CompletableFuture.completedFuture(null);
                    });
            instanceService = new InstanceServiceImpl(clock, computeBackend, eventBus, instanceStore);
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
                Assertions.assertThat(instance.name()).isEqualTo(INSTANCE_NAME);
            }

            @Test void It_should_have_the_requested_cpu() {
                Assertions.assertThat(instance.cpu()).isEqualTo(DEFAULT_CPU);
            }

            @Test void It_should_have_the_requested_memory() {
                Assertions.assertThat(instance.memory()).isEqualTo(DEFAULT_MEMORY);
            }

            @Test void It_should_have_state_starting() {
                Assertions.assertThat(instance.state()).isEqualTo(InstanceState.STARTING);
            }

            @Test void It_should_return_an_instance_id() {
                Assertions.assertThat(instance.id()).isNotNull();
                Assertions.assertThat(instance.id()).startsWith("i-");
            }

            @Test void It_should_have_creation_time() {
                Assertions.assertThat(instance.createdAt()).isEqualTo(createdAt);
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
                    instance = instanceService.get(GetInstanceRequest.builder()
                            .instanceId(instance.id()).build());
                }

                @Test void It_should_throw_exception() {
                    Assertions.assertThatThrownBy(() -> instanceService.create(request)).hasMessageContainingAll(
                            INSTANCE_NAME, "already exists"
                    );
                }

                @Test void It_should_only_create_one_instance() {
                    Assertions.assertThat(instanceService.list(new ListInstanceRequest())).hasSize(1);
                    Assertions.assertThat(instanceService.list(new ListInstanceRequest()).getFirst()).isEqualTo(instance);
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
                    Assertions.assertThat(instance1.id()).isNotEqualTo(instance2.id());
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
                    response = instanceService.list(new ListInstanceRequest());
                }

                @Test void It_should_return_a_list_of_one_instance_stored() {
                    Assertions.assertThat(response).hasSize(1);
                }

                @Test void Stored_instance_should_equal_expected() {
                    Assertions.assertThat(response).hasSize(1);
                    Instance responseInstance = response.getFirst();
                    Assertions.assertThat(responseInstance.id()).isEqualTo(expected.id());
                    Assertions.assertThat(responseInstance.name()).isEqualTo(expected.name());
                    Assertions.assertThat(responseInstance.cpu()).isEqualTo(expected.cpu());
                    Assertions.assertThat(responseInstance.memory()).isEqualTo(expected.memory());
                    Assertions.assertThat(responseInstance.createdAt()).isEqualTo(expected.createdAt());
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
                            .instanceId(expected.id())
                            .build();

                    response = instanceService.get(request);
                }

                @Test
                void It_should_return_the_instance() {
                    Assertions.assertThat(response.id()).isEqualTo(expected.id());
                    Assertions.assertThat(response.name()).isEqualTo(expected.name());
                    Assertions.assertThat(response.cpu()).isEqualTo(expected.cpu());
                    Assertions.assertThat(response.memory()).isEqualTo(expected.memory());
                }
            }

            @Nested
            class With_instance_name {
                Instance response;

                @BeforeEach
                void setup() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .name(expected.name())
                            .build();

                    response = instanceService.get(request);
                }

                @Test
                void It_should_return_the_instance() {
                    Assertions.assertThat(response.id()).isEqualTo(expected.id());
                    Assertions.assertThat(response.name()).isEqualTo(expected.name());
                    Assertions.assertThat(response.cpu()).isEqualTo(expected.cpu());
                    Assertions.assertThat(response.memory()).isEqualTo(expected.memory());
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
                            .hasMessageContaining("Instance not found");
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
                expected = instanceService.get(GetInstanceRequest.builder()
                        .instanceId(expected.id()).build());
            }

            @Nested
            class With_instance_id {
                @BeforeEach
                void setup() {
                    DeleteInstanceRequest request = DeleteInstanceRequest.builder()
                            .instanceId(expected.id())
                            .build();

                    response = instanceService.delete(request);
                }

                @Test
                void It_should_return_the_deleted_instance() {
                    Assertions.assertThat(response).isEqualTo(expected);
                }

                @Test
                void Instance_should_no_longer_be_listable() {
                    Assertions.assertThat(instanceService.list(new ListInstanceRequest())).isEmpty();
                }

                @Test
                void Instance_should_no_longer_be_gettable_by_id() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .instanceId(expected.id())
                            .build();

                    Assertions.assertThatThrownBy(() -> instanceService.get(request))
                            .hasMessageContaining("Instance not found");
                }

                @Test
                void Instance_should_no_longer_be_gettable_by_name() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .name(expected.name())
                            .build();

                    Assertions.assertThatThrownBy(() -> instanceService.get(request))
                            .hasMessageContaining("Instance not found");
                }
            }

            @Nested
            class With_instance_name {
                @BeforeEach
                void setup() {
                    DeleteInstanceRequest request = DeleteInstanceRequest.builder()
                            .name(expected.name())
                            .build();

                    response = instanceService.delete(request);
                }

                @Test
                void It_should_return_the_deleted_instance() {
                    Assertions.assertThat(response).isEqualTo(expected);
                }

                @Test
                void Instance_should_no_longer_be_listable() {
                    Assertions.assertThat(instanceService.list(new ListInstanceRequest())).isEmpty();
                }

                @Test
                void Instance_should_no_longer_be_gettable_by_id() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .instanceId(expected.id())
                            .build();

                    Assertions.assertThatThrownBy(() -> instanceService.get(request))
                            .hasMessageContaining("Instance not found");
                }

                @Test
                void Instance_should_no_longer_be_gettable_by_name() {
                    GetInstanceRequest request = GetInstanceRequest.builder()
                            .name(expected.name())
                            .build();

                    Assertions.assertThatThrownBy(() -> instanceService.get(request))
                            .hasMessageContaining("Instance not found");
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
                    .hasMessageContaining("Instance not found");
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

                instanceService.stop(StopInstanceRequest.builder().instanceId(instance.id()).build());
            }

            @Test
            void It_should_throw_for_unknown_instance() {
                StartInstanceRequest request = StartInstanceRequest.builder()
                        .instanceId("i-does-not-exist")
                        .build();

                Assertions.assertThatThrownBy(() -> instanceService.start(request))
                        .hasMessageContaining("Instance not found");
            }

            @Test
            void It_should_set_state_to_starting() {
                StartInstanceRequest request = StartInstanceRequest.builder()
                        .instanceId(instance.id())
                        .build();

                Instance response = instanceService.start(request);

                Assertions.assertThat(response).isEqualTo(instance);
                Assertions.assertThat(response.state()).isEqualTo(InstanceState.STARTING);
            }

            @Test
            void It_should_support_lookup_by_name() {
                StartInstanceRequest request = StartInstanceRequest.builder()
                        .name(instance.name())
                        .build();

                Instance response = instanceService.start(request);

                Assertions.assertThat(response.state()).isEqualTo(InstanceState.STARTING);
            }

            @Test
            void It_should_reject_an_instance_that_cannot_be_started() {
                instance = instanceService.create(CreateInstanceRequest.builder()
                        .name(INSTANCE_NAME + "a")
                        .cpu(DEFAULT_CPU)
                        .memory(DEFAULT_MEMORY)
                        .build());

                StartInstanceRequest request = StartInstanceRequest.builder()
                        .instanceId(instance.id())
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
            }

            @Test
            void It_should_set_state_to_stopping() {
                StopInstanceRequest request = StopInstanceRequest.builder()
                        .instanceId(instance.id())
                        .build();

                Instance response = instanceService.stop(request);

                Assertions.assertThat(response.id()).isEqualTo(instance.id());
                Assertions.assertThat(response.state()).isEqualTo(InstanceState.STOPPING);
            }

            @Test
            void It_should_support_lookup_by_name() {
                StopInstanceRequest request = StopInstanceRequest.builder()
                        .name(instance.name())
                        .build();

                Instance response = instanceService.stop(request);

                Assertions.assertThat(response.state()).isEqualTo(InstanceState.STOPPING);
            }

            @Test
            void It_should_reject_an_instance_that_cannot_be_stopped() {
                StopInstanceRequest request = StopInstanceRequest.builder()
                        .instanceId(instance.id())
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
            Assertions.assertThat(instanceService.list(new ListInstanceRequest())).hasSize(1);
        }

        @Nested
        class When_reconciling_existing_instances {

            @Test
            void It_should_start_a_running_instance_that_is_stopped_in_compute() {
                Instance instance = storeExistingInstance(InstanceState.RUNNING);

                reconcile(instance, Map.of(instance.id(), ComputeStatus.STOPPED));

                Mockito.verify(computeBackend).start(instance);
                Assertions.assertThat(instancesById.get(instance.id()).state())
                        .isEqualTo(InstanceState.RUNNING);
            }

            @Test
            void It_should_recreate_and_start_a_missing_running_instance() {
                Instance instance = storeExistingInstance(InstanceState.RUNNING);

                reconcile(instance, Map.of(instance.id(), ComputeStatus.MISSING));

                InOrder inOrder = Mockito.inOrder(computeBackend);
                inOrder.verify(computeBackend).create(instance);
                inOrder.verify(computeBackend).start(instance);
            }

            @Test
            void It_should_stop_a_stopped_instance_that_is_running_in_compute() {
                Instance instance = storeExistingInstance(InstanceState.STOPPED);

                reconcile(instance, Map.of(instance.id(), ComputeStatus.RUNNING));

                Mockito.verify(computeBackend).stop(instance);
                Assertions.assertThat(instancesById.get(instance.id()).state())
                        .isEqualTo(InstanceState.STOPPED);
            }

            @Test
            void It_should_complete_a_start_transition_after_the_compute_instance_is_running() {
                Instance instance = storeExistingInstance(InstanceState.STARTING);

                reconcile(instance, Map.of(instance.id(), ComputeStatus.RUNNING));

                Assertions.assertThat(instancesById.get(instance.id()).state())
                        .isEqualTo(InstanceState.RUNNING);
                Mockito.verify(computeBackend, Mockito.never()).start(instance);
            }

            @Test
            void It_should_complete_a_stop_transition_after_stopping_the_compute_instance() {
                Instance instance = storeExistingInstance(InstanceState.STOPPING);

                reconcile(instance, Map.of(instance.id(), ComputeStatus.RUNNING));

                Mockito.verify(computeBackend).stop(instance);
                Assertions.assertThat(instancesById.get(instance.id()).state())
                        .isEqualTo(InstanceState.STOPPED);
            }

            @Test
            void It_should_treat_an_omitted_compute_status_as_missing() {
                Instance instance = storeExistingInstance(InstanceState.RUNNING);

                reconcile(instance, Map.of());

                Mockito.verify(computeBackend).create(instance);
                Mockito.verify(computeBackend).start(instance);
            }

            @Test
            void It_should_mark_an_instance_missing_when_reconciliation_fails() {
                Instance instance = storeExistingInstance(InstanceState.STARTING);
                Mockito.when(computeBackend.start(instance))
                        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("failure")));

                reconcile(instance, Map.of(instance.id(), ComputeStatus.STOPPED));

                Assertions.assertThat(instancesById.get(instance.id()).state())
                        .isEqualTo(InstanceState.MISSING);
            }

            @Test
            void It_should_not_change_an_instance_already_marked_missing() {
                Instance instance = storeExistingInstance(InstanceState.MISSING);

                reconcile(instance, Map.of(instance.id(), ComputeStatus.RUNNING));

                Mockito.verify(computeBackend, Mockito.never()).create(instance);
                Mockito.verify(computeBackend, Mockito.never()).start(instance);
                Mockito.verify(computeBackend, Mockito.never()).stop(instance);
                Assertions.assertThat(instancesById.get(instance.id()).state())
                        .isEqualTo(InstanceState.MISSING);
            }

            private Instance storeExistingInstance(InstanceState state) {
                Instance instance = Instance.builder()
                        .id("i-existing-" + state)
                        .name("existing-" + state)
                        .cpu(DEFAULT_CPU)
                        .memory(DEFAULT_MEMORY)
                        .state(state)
                        .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                        .build();
                instancesById.put(instance.id(), instance);
                idsByName.put(instance.name(), instance.id());
                return instance;
            }

            private void reconcile(Instance instance, Map<String, ComputeStatus> statuses) {
                Mockito.clearInvocations(computeBackend, instanceStore);
                Mockito.when(instanceStore.list())
                        .thenReturn(CompletableFuture.completedFuture(List.of(instance)));
                Mockito.when(computeBackend.describeStatuses(List.of(instance)))
                        .thenReturn(statuses);

                new InstanceServiceImpl(clock, computeBackend, eventBus, instanceStore);
            }
        }

        @Nested class When_listening_for_events {

            @Test void Instance_service_should_subscribe_to_event_bus_for_instance_health_event() {
                Mockito.verify(eventBus).subscribe(Mockito.eq(InstanceHealthEvent.class), Mockito.any());
            }

            @Nested class And_an_instance_unhealthy_event_is_received {
                Instance instanceBefore;
                Instance instanceAfter;
                @BeforeEach
                void setup() {
                    instanceBefore =  instanceService.create(CreateInstanceRequest.builder()
                            .name(INSTANCE_NAME)
                            .cpu(DEFAULT_CPU)
                            .memory(DEFAULT_MEMORY)
                            .build());

                    eventBus.publish(new InstanceHealthEvent(instanceBefore.id(), EventAction.UNHEALTHY));

                    Awaitility.await()
                            .untilAsserted(() -> {
                                instanceAfter = instanceService.get(
                                        GetInstanceRequest.builder()
                                                .instanceId(instanceBefore.id())
                                                .build());

                                Assertions.assertThat(instanceAfter.state())
                                        .isEqualTo(InstanceState.MISSING);
                            });
                }

                @Test void Instance_should_be_marked_missing() {
                    Assertions.assertThat(instanceAfter.state()).isEqualTo(InstanceState.MISSING);
                }
            }

            @Nested class And_a_missing_instance_receives_a_healthy_event {
                Instance instanceBefore;
                Instance instanceAfter;
                @BeforeEach
                void setup() {
                    instanceBefore =  instanceService.create(CreateInstanceRequest.builder()
                            .name(INSTANCE_NAME)
                            .cpu(DEFAULT_CPU)
                            .memory(DEFAULT_MEMORY)
                            .build());

                    eventBus.publish(new InstanceHealthEvent(instanceBefore.id(), EventAction.UNHEALTHY));

                    Awaitility.await()
                            .untilAsserted(() -> {
                                instanceAfter = instanceService.get(
                                        GetInstanceRequest.builder()
                                                .instanceId(instanceBefore.id())
                                                .build());

                                Assertions.assertThat(instanceAfter.state())
                                        .isEqualTo(InstanceState.MISSING);
                            });

                    eventBus.publish(new InstanceHealthEvent(instanceBefore.id(), EventAction.HEALTHY));

                    Awaitility.await()
                            .untilAsserted(() -> {
                                instanceAfter = instanceService.get(
                                        GetInstanceRequest.builder()
                                                .instanceId(instanceBefore.id())
                                                .build());
                                Assertions.assertThat(instanceAfter.state())
                                        .isEqualTo(InstanceState.RUNNING);
                            });
                }

                @Test void Instance_should_be_marked_running() {
                    Assertions.assertThat(instanceAfter.state()).isEqualTo(InstanceState.RUNNING);
                }
            }

            @Nested class And_a_non_missing_instance_receives_a_healthy_event {
                Instance instanceBefore;
                Instance instanceAfter;
                @BeforeEach
                void setup() {
                    instanceBefore =  instanceService.create(CreateInstanceRequest.builder()
                            .name(INSTANCE_NAME)
                            .cpu(DEFAULT_CPU)
                            .memory(DEFAULT_MEMORY)
                            .build());

                    eventBus.publish(new InstanceHealthEvent(instanceBefore.id(), EventAction.HEALTHY));

                    Awaitility.await()
                            .untilAsserted(() -> {
                                instanceAfter = instanceService.get(
                                        GetInstanceRequest.builder()
                                                .instanceId(instanceBefore.id())
                                                .build());
                                Assertions.assertThat(instanceAfter.state())
                                        .isEqualTo(InstanceState.RUNNING);
                            });
                }

                @Test void Instance_should_be_marked_running() {
                    Assertions.assertThat(instanceAfter.state()).isEqualTo(InstanceState.RUNNING);
                }
            }
        }
    }
}
