package jc121f1.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.javalin.Javalin;
import io.javalin.testtools.HttpClient;
import io.javalin.testtools.Response;
import jc121f1.annotations.MiniCloudTest;
import jc121f1.dagger.instance.DaggerInstanceWebServiceComponent;
import jc121f1.dagger.instance.InstanceWebServiceComponent;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.dao.Instance;
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

import java.time.Duration;
import java.util.List;

@MiniCloudTest
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstanceServiceEndToEndTest {
    private static final Duration ASYNC_OPERATION_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration ASYNC_OPERATION_POLL_INTERVAL = Duration.ofSeconds(1);

    private Javalin webService;

    private final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String instanceId;
    private Instance createdInstance;

    private HttpClient client;
    private DockerClient dockerClient;

    @BeforeAll void beforeAll() {
        InstanceWebServiceComponent component = DaggerInstanceWebServiceComponent.create();
        dockerClient = component.dockerClient();
        webService = new InstanceWebService(component).create();

        webService.start(7070);

        client = new HttpClient(webService, java.net.http.HttpClient.newHttpClient());
    }

    @AfterAll void afterAll() {
        webService.stop();
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(10)
    @Nested class CreateInstanceBlock {
        Response createResponse;

        @SneakyThrows
        @BeforeAll void setup() {
            createResponse = client.post(
                    "/instances",
                    """
                    {
                        "name": "e2e-instance",
                        "cpu": 2,
                        "memory": 8
                    }
                    """
            );

            createdInstance = OBJECT_MAPPER.readValue(
                    createResponse.body().string(),
                    Instance.class
            );

            instanceId = createdInstance.getId();
        }

        @Order(11)
        @Test void It_should_return_200() {
            Assertions.assertThat(createResponse.code())
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
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(15)
    @Nested class DuplicateInstanceBlock {
        @Test void It_should_reject_a_duplicate_name() {
            Response duplicateResponse = client.post(
                    "/instances",
                    """
                    {
                        "name": "e2e-instance",
                        "cpu": 2,
                        "memory": 8
                    }
                    """
            );

            Assertions.assertThat(duplicateResponse.code()).isNotEqualTo(200);
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(20)
    @Nested class ListInstanceBlock {
        Response listResponse;

        @BeforeAll void setup() {
            listResponse = client.get("/instances");
        }

        @Order(21)
        @Test void It_should_return_200() {
            Assertions.assertThat(listResponse.code())
                    .isEqualTo(200);
        }

        @Order(22)
        @SneakyThrows
        @Test void It_should_return_created_instance() {
            List<Instance> instances = OBJECT_MAPPER
                    .readerForListOf(Instance.class)
                    .readValue(listResponse.body().string());

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
        Response describeResponse;

        @BeforeAll void setup() {
            describeResponse = client.post(
                "/instances/describe",
                """
                {
                    "instanceId": "%s"
                }
                """.formatted(instanceId));
        }

        @Order(31)
        @Test void It_should_return_200() {
            Assertions.assertThat(describeResponse.code())
                    .isEqualTo(200);
        }

        @Order(32)
        @SneakyThrows
        @Test void It_should_return_created_instance() {
            Instance described = OBJECT_MAPPER.readValue(
                    describeResponse.body().string(),
                    Instance.class
            );

            Assertions.assertThat(described).isEqualTo(createdInstance);
        }

        @Order(33)
        @SneakyThrows
        @Test void It_should_find_the_created_instance_by_name() {
            Response response = client.post(
                    "/instances/describe",
                    """
                    {
                        "name": "e2e-instance"
                    }
                    """
            );

            Assertions.assertThat(response.code()).isEqualTo(200);
            Instance described = OBJECT_MAPPER.readValue(response.body().string(), Instance.class);
            Assertions.assertThat(described.getId()).isEqualTo(instanceId);
        }

        @Order(34)
        @Test void It_should_reject_a_request_without_an_identifier() {
            Response response = client.post("/instances/describe", "{}");

            Assertions.assertThat(response.code()).isNotEqualTo(200);
        }

        @Order(35)
        @Test void It_should_reject_an_unknown_identifier() {
            Response response = client.post(
                    "/instances/describe",
                    """
                    {
                        "instanceId": "i-does-not-exist"
                    }
                    """
            );

            Assertions.assertThat(response.code()).isNotEqualTo(200);
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
        Response stopResponse;
        Instance stoppingInstance;

        @SneakyThrows
        @BeforeAll void setup() {
            stopResponse = client.post(
                    "/instances/stop",
                    """
                    {
                        "instanceId": "%s"
                    }
                    """.formatted(instanceId));
            stoppingInstance = OBJECT_MAPPER.readValue(
                    stopResponse.body().string(),
                    Instance.class
            );
        }

        @Order(51)
        @Test void It_should_return_200() {
            Assertions.assertThat(stopResponse.code()).isEqualTo(200);
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
        Response startResponse;
        Instance startingInstance;

        @SneakyThrows
        @BeforeAll void setup() {
            startResponse = client.post(
                    "/instances/start",
                    """
                    {
                        "instanceId": "%s"
                    }
                    """.formatted(instanceId)
            );
            startingInstance = OBJECT_MAPPER.readValue(
                    startResponse.body().string(),
                    Instance.class
            );
        }

        @Order(61)
        @Test void It_should_return_200() {
            Assertions.assertThat(startResponse.code()).isEqualTo(200);
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
            Response response = client.post(
                    "/instances/start",
                    """
                    {
                        "instanceId": "%s"
                    }
                    """.formatted(instanceId)
            );

            Assertions.assertThat(response.code()).isNotEqualTo(200);
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @Order(70)
    @Nested class DeleteInstanceBlock {
        Response deleteResponse;
        @SneakyThrows
        @BeforeAll void setup() {
            deleteResponse = client.post(
                    "/instances/delete",
                    """
                    {
                        "instanceId": "%s"
                    }
                    """.formatted(instanceId)
            );
        }

        @Order(71)
        @Test void It_should_return_200() {
            Assertions.assertThat(deleteResponse.code()).isEqualTo(200);
        }

        @Order(72)
        @Test void It_should_delete_instance() {
            assertInstanceDeletedEventually();
        }

        @Order(73)
        @Test void It_should_no_longer_be_describable() {
            Response response = client.post(
                    "/instances/describe",
                    """
                    {
                        "instanceId": "%s"
                    }
                    """.formatted(instanceId)
            );

            Assertions.assertThat(response.code()).isNotEqualTo(200);
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
                    Response response = client.post(
                            "/instances/describe",
                            """
                            {
                                "instanceId": "%s"
                            }
                            """.formatted(instanceId)
                    );
                    String body = response.body().string();

                    Assertions.assertThat(response.code())
                            .as("describe response body: %s", body)
                            .isEqualTo(200);
                    Instance described = OBJECT_MAPPER.readValue(body, Instance.class);
                    Assertions.assertThat(described.getState())
                            .as("describe response body: %s", body)
                            .isEqualTo(expectedState);
                });
    }

    @SneakyThrows
    private void assertInstanceDeletedEventually() {
        Awaitility.await("instance " + instanceId + " to be deleted")
                .atMost(ASYNC_OPERATION_TIMEOUT)
                .pollInterval(ASYNC_OPERATION_POLL_INTERVAL)
                .untilAsserted(() -> {
                    Response response = client.get("/instances");
                    String body = response.body().string();

                    Assertions.assertThat(response.code())
                            .as("list response body: %s", body)
                            .isEqualTo(200);
                    List<Instance> remaining = OBJECT_MAPPER
                            .readerForListOf(Instance.class)
                            .readValue(body);
                    Assertions.assertThat(remaining)
                            .as("list response body: %s", body)
                            .noneMatch(instance -> instance.getId().equals(instanceId));
                });
    }
}
