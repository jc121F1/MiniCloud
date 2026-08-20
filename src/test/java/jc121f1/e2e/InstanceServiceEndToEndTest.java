package jc121f1.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.testtools.HttpClient;
import io.javalin.testtools.Response;
import jc121f1.annotations.MiniCloudTest;
import jc121f1.dagger.DaggerWebserviceComponent;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.dao.Instance;
import jc121f1.wbs.WebService;
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

    private Javalin webService;

    private final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String instanceId;
    private Instance createdInstance;

    private HttpClient client;

    @BeforeAll void beforeAll() {
        webService = WebService.create(
                DaggerWebserviceComponent.create()
        );

        webService.start(7070);

        client = new HttpClient(webService, java.net.http.HttpClient.newHttpClient());
    }

    @AfterAll void afterAll() {
        webService.stop();
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Order(40)
    @Nested class WaitUntilRunningBlock {
        @Test void Instance_should_become_running() {
            Awaitility.await()
                    .atMost(Duration.ofMinutes(5))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                var awaitResponse = client.post(
                        "/instances/describe",
                        """
                                {
                                    "instanceId": "%s"
                                }
                                """.formatted(instanceId));
                Instance awaitDescribed = OBJECT_MAPPER.readValue(
                        awaitResponse.body().string(),
                        Instance.class);
                Assertions.assertThat(awaitDescribed.getState()).isEqualTo(InstanceState.RUNNING);
            });
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
            Awaitility.await()
                    .atMost(Duration.ofMinutes(5))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        var awaitResponse = client.post(
                                "/instances/describe",
                                """
                                        {
                                            "instanceId": "%s"
                                        }
                                        """.formatted(instanceId));
                        Instance awaitDescribed = OBJECT_MAPPER.readValue(
                                awaitResponse.body().string(),
                                Instance.class);
                        Assertions.assertThat(awaitDescribed.getState()).isEqualTo(InstanceState.STOPPED);
                    });
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
            Awaitility.await()
                    .atMost(Duration.ofMinutes(5))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        var awaitResponse = client.post(
                                "/instances/describe",
                                """
                                        {
                                            "instanceId": "%s"
                                        }
                                        """.formatted(instanceId));
                        Instance awaitDescribed = OBJECT_MAPPER.readValue(
                                awaitResponse.body().string(),
                                Instance.class);
                        Assertions.assertThat(awaitDescribed.getState()).isEqualTo(InstanceState.RUNNING);
                    });
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
            Awaitility.await()
                    .atMost(Duration.ofMinutes(5))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        var finalListResponse = client.get("/instances");
                        List<Instance> remaining = OBJECT_MAPPER
                                .readerForListOf(Instance.class)
                                .readValue(
                                        finalListResponse.body().string()
                                );
                        Assertions.assertThat(remaining)
                                .noneMatch(instance ->
                                        instance.getId().equals(instanceId)
                                );
                    });
        }
    }
}