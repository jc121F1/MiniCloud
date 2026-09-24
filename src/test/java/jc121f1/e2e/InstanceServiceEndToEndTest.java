package jc121f1.e2e;

import com.github.dockerjava.api.DockerClient;
import io.javalin.Javalin;
import jc121f1.annotations.MiniCloudTest;
import jc121f1.dagger.auth.AuthWebServiceComponent;
import jc121f1.dagger.auth.DaggerAuthWebServiceComponent;
import jc121f1.dagger.instance.DaggerInstanceWebServiceComponent;
import jc121f1.dagger.instance.InstanceWebServiceComponent;
import jc121f1.minicloud.client.ApiClient;
import jc121f1.minicloud.client.ApiException;
import jc121f1.minicloud.client.ApiResponse;
import jc121f1.minicloud.client.api.InstanceApi;
import jc121f1.minicloud.client.api.UserApi;
import jc121f1.minicloud.client.model.CreateInstanceRequest;
import jc121f1.minicloud.client.model.CreateUserRequest;
import jc121f1.minicloud.client.model.DeleteInstanceRequest;
import jc121f1.minicloud.client.model.GetInstanceRequest;
import jc121f1.minicloud.client.model.Instance;
import jc121f1.minicloud.client.model.InstanceState;
import jc121f1.minicloud.client.model.LoginRequest;
import jc121f1.minicloud.client.model.StartInstanceRequest;
import jc121f1.minicloud.client.model.StopInstanceRequest;
import jc121f1.wbs.services.AuthWebService;
import jc121f1.wbs.services.InstanceWebService;
import lombok.SneakyThrows;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@MiniCloudTest
@EnabledIfEnvironmentVariable(named = "DynamoDbLocalAvailable", matches = "True")
@EnabledIfEnvironmentVariable(named = "DockerE2EAvailable", matches = "True")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstanceServiceEndToEndTest {
    private static final Duration ASYNC_OPERATION_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration ASYNC_OPERATION_POLL_INTERVAL = Duration.ofSeconds(1);

    private Javalin webService;
    private Javalin authWebService;

    private String instanceId;
    private Instance createdInstance;

    private DockerClient dockerClient;
    private InstanceApi instanceApi;

    @BeforeAll void beforeAll() {
        InstanceWebServiceComponent component = DaggerInstanceWebServiceComponent.create();
        dockerClient = component.dockerClient();

        String bearerToken = setupAuth();
        webService = new InstanceWebService(component).create();
        webService.start(0);

        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri("http://localhost:" + webService.port());
        apiClient.setRequestInterceptor(request -> request.header("Authorization", "Bearer " + bearerToken));
        instanceApi = new InstanceApi(apiClient);
    }

    @AfterAll void afterAll() {
        try {
            if (webService != null) {
                webService.stop();
            }
        } finally {
            if (authWebService != null) {
                authWebService.stop();
            }
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(10)
    @Nested class CreateInstanceBlock {
        ApiResponse<Instance> createResponse;

        @SneakyThrows
        @BeforeAll void setup() {
            createResponse = instanceApi.createInstanceWithHttpInfo(
                    new CreateInstanceRequest().cpu(2).memory(8).name("e2e-instance")
            );
            createdInstance = createResponse.getData();

            instanceId = createdInstance.getId();
        }

        @Order(11)
        @Test void It_should_return_200() {
            Assertions.assertThat(createResponse.getStatusCode())
                    .isEqualTo(200);
        }

        @Order(12)
        @Test void It_should_return_expected_instance() {

            Assertions.assertThat(instanceId)
                    .startsWith("i-");

            Assertions.assertThat(createdInstance.getName())
                    .isEqualTo("e2e-instance");

            Assertions.assertThat(createdInstance.getCpu())
                    .isEqualTo(2);

            Assertions.assertThat(createdInstance.getMemory())
                    .isEqualTo(8);
        }

        @Order(13)
        @Test void Instance_should_become_running() {
            assertInstanceStateEventually(InstanceState.RUNNING);
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(15)
    @Nested class DuplicateInstanceBlock {
        @Test void It_should_reject_a_duplicate_name() {
            Assertions.assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> instanceApi.createInstance(
                            new CreateInstanceRequest().cpu(2).memory(8).name("e2e-instance")))
                    .satisfies(exception -> Assertions.assertThat(exception.getCode())
                            .isBetween(400, 599));
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(20)
    @Nested class ListInstanceBlock {
        ApiResponse<List<Instance>> listResponse;

        @SneakyThrows
        @BeforeAll void setup() {
            listResponse = instanceApi.listInstancesWithHttpInfo();
        }

        @Order(21)
        @Test void It_should_return_200() {
            Assertions.assertThat(listResponse.getStatusCode())
                    .isEqualTo(200);
        }

        @Order(22)
        @SneakyThrows
        @Test void It_should_return_created_instance() {
            List<Instance> instances = listResponse.getData();

            Assertions.assertThat(instances)
                    .anyMatch(instance ->
                            instance.getId().equals(instanceId)
                    );
        }

    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(30)
    @Nested class DescribeInstanceBlock {
        ApiResponse<Instance> describeResponse;

        @SneakyThrows
        @BeforeAll void setup() {
            describeResponse = instanceApi.describeInstanceWithHttpInfo(
                    new GetInstanceRequest().instanceId(instanceId)
            );
        }

        @Order(31)
        @Test void It_should_return_200() {
            Assertions.assertThat(describeResponse.getStatusCode())
                    .isEqualTo(200);
        }

        @Order(32)
        @SneakyThrows
        @Test void It_should_return_created_instance() {
            Instance described = describeResponse.getData();

            assertInstanceEqualExceptState(described, createdInstance);
        }

        @Order(33)
        @SneakyThrows
        @Test void It_should_find_the_created_instance_by_name() {
            ApiResponse<Instance> response = instanceApi.describeInstanceWithHttpInfo(
                    new GetInstanceRequest().name("e2e-instance")
            );

            Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
            Assertions.assertThat(response.getData().getId()).isEqualTo(instanceId);
        }

        @Order(34)
        @Test void It_should_reject_a_request_without_an_identifier() {
            Assertions.assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> instanceApi.describeInstance(new GetInstanceRequest()))
                    .satisfies(exception -> Assertions.assertThat(exception.getCode())
                            .isBetween(400, 599));
        }

        @Order(35)
        @Test void It_should_reject_an_unknown_identifier() {
            Assertions.assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> instanceApi.describeInstance(
                            new GetInstanceRequest().instanceId("i-does-not-exist")))
                    .satisfies(exception -> Assertions.assertThat(exception.getCode())
                            .isBetween(400, 599));
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(40)
    @Nested class WaitUntilRunningBlock {
        @Test void Instance_should_become_running() {
            assertInstanceStateEventually(InstanceState.RUNNING);
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(50)
    @Nested class StopInstanceBlock {
        ApiResponse<Instance> stopResponse;
        Instance stoppingInstance;

        @SneakyThrows
        @BeforeAll void setup() {
            stopResponse = instanceApi.stopInstanceWithHttpInfo(
                    new StopInstanceRequest().instanceId(instanceId)
            );
            stoppingInstance = stopResponse.getData();
        }

        @Order(51)
        @Test void It_should_return_200() {
            Assertions.assertThat(stopResponse.getStatusCode()).isEqualTo(200);
        }

        @Order(52)
        @Test void It_should_return_stopping_instance() {
            Assertions.assertThat(stoppingInstance.getState()).isEqualTo(InstanceState.STOPPING);
        }

        @Order(53)
        @SneakyThrows
        @Test void Instance_should_become_stopped() {
            assertInstanceStateEventually(InstanceState.STOPPED);
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(60)
    @Nested class StartInstanceBlock {
        ApiResponse<Instance> startResponse;
        Instance startingInstance;

        @SneakyThrows
        @BeforeAll void setup() {
            startResponse = instanceApi.startInstanceWithHttpInfo(
                    new StartInstanceRequest().instanceId(instanceId)
            );
            startingInstance = startResponse.getData();
        }

        @Order(61)
        @Test void It_should_return_200() {
            Assertions.assertThat(startResponse.getStatusCode()).isEqualTo(200);
        }

        @Order(62)
        @Test void It_should_return_starting_instance() {
            Assertions.assertThat(startingInstance.getState()).isEqualTo(InstanceState.STARTING);
        }

        @Order(63)
        @Test void It_should_become_running() {
            assertInstanceStateEventually(InstanceState.RUNNING);
        }

        @Order(64)
        @Test void It_should_reject_starting_an_already_running_instance() {
            Assertions.assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> instanceApi.startInstance(new StartInstanceRequest().instanceId(instanceId)))
                    .satisfies(exception -> Assertions.assertThat(exception.getCode())
                            .isBetween(400, 599));
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(70)
    @Nested class DeleteInstanceBlock {
        ApiResponse<Instance> deleteResponse;
        @SneakyThrows
        @BeforeAll void setup() {
            deleteResponse = instanceApi.deleteInstanceWithHttpInfo(
                    new DeleteInstanceRequest().instanceId(instanceId)
            );
        }

        @Order(71)
        @Test void It_should_return_200() {
            Assertions.assertThat(deleteResponse.getStatusCode()).isEqualTo(200);
        }

        @Order(72)
        @Test void It_should_delete_instance() {
            assertInstanceDeletedEventually();
        }

        @Order(73)
        @Test void It_should_no_longer_be_describable() {
            Assertions.assertThatExceptionOfType(ApiException.class)
                    .isThrownBy(() -> instanceApi.describeInstance(new GetInstanceRequest().instanceId(instanceId)))
                    .satisfies(exception -> Assertions.assertThat(exception.getCode())
                            .isBetween(400, 599));
        }

        @Order(74)
        @Test void It_should_remove_the_container() {
            Awaitility.await("container for " + instanceId + " to be removed")
                    .atMost(ASYNC_OPERATION_TIMEOUT)
                    .pollInterval(ASYNC_OPERATION_POLL_INTERVAL)
                    .untilAsserted(() -> Assertions.assertThat(
                                    dockerClient.listContainersCmd().withShowAll(true).exec()
                            )
                            .as("all Docker container names")
                            .noneMatch(container -> container.getNames() != null
                                    && java.util.Arrays.stream(container.getNames())
                                    .anyMatch(name -> name.equals("/MiniCloud-" + instanceId))));
        }
    }

    @SneakyThrows
    private void assertInstanceStateEventually(InstanceState expectedState) {
        Awaitility.await("instance " + instanceId + " to become " + expectedState)
                .atMost(ASYNC_OPERATION_TIMEOUT)
                .pollInterval(ASYNC_OPERATION_POLL_INTERVAL)
                .untilAsserted(() -> {
                    Instance described = instanceApi.describeInstance(
                            new GetInstanceRequest().instanceId(instanceId)
                    );
                    Assertions.assertThat(described.getState())
                            .as("described instance: %s", described)
                            .isEqualTo(expectedState);
                });
    }

    @SneakyThrows
    private void assertInstanceDeletedEventually() {
        Awaitility.await("instance " + instanceId + " to be deleted")
                .atMost(ASYNC_OPERATION_TIMEOUT)
                .pollInterval(ASYNC_OPERATION_POLL_INTERVAL)
                .untilAsserted(() -> {
                    List<Instance> remaining = instanceApi.listInstances();
                    Assertions.assertThat(remaining)
                            .as("remaining instances: %s", remaining)
                            .noneMatch(instance -> instance.getId().equals(instanceId));
                });
    }

    private void assertInstanceEqualExceptState(Instance instance1, Instance instance2) {
        Assertions.assertThat(instance1.getCpu()).isEqualTo(instance2.getCpu());
        Assertions.assertThat(instance1.getId()).isEqualTo(instance2.getId());
        Assertions.assertThat(instance1.getName()).isEqualTo(instance2.getName());
        Assertions.assertThat(instance1.getMemory()).isEqualTo(instance2.getMemory());
    }

    @SneakyThrows
    private String setupAuth() {
        AuthWebServiceComponent authWebServiceComponent = DaggerAuthWebServiceComponent.create();
        authWebService = new AuthWebService(authWebServiceComponent).create();
        authWebService.start(0);

        ApiClient apiClient = new ApiClient();
        apiClient.updateBaseUri("http://localhost:" + authWebService.port());
        UserApi userApi = new UserApi(apiClient);
        String email = "instance-e2e-" + UUID.randomUUID() + "@example.com";
        String password = UUID.randomUUID().toString();

        var user = userApi.createUser(new CreateUserRequest().userEmail(email).password(password));
        var session = userApi.login(new LoginRequest().email(email).password(password));
        Assertions.assertThat(session.getAccountId()).isEqualTo(user.getAccountId());
        Assertions.assertThat(session.getToken()).isNotBlank();
        return session.getToken();
    }
}
