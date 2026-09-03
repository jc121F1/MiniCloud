package jc121f1.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import jc121f1.annotations.MiniCloudTest;
import jc121f1.dagger.instance.InstanceWebServiceComponent;
import jc121f1.integration.testdagger.DaggerTestInstanceWebServiceComponent;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.store.InstanceStore;
import jc121f1.wbs.services.InstanceWebService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@MiniCloudTest
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class InstanceApiIntegrationTest {

    private ComputeBackend computeBackend;
    private InstanceStore instanceStore;
    private Javalin app;
    private final Instant createdAtInstant = Instant.now();

    @BeforeEach
    void setUp() {
        InstanceWebServiceComponent component =
                DaggerTestInstanceWebServiceComponent.create();

        computeBackend = component.computeBackend();

        instanceStore = component.instanceStore();

        Instance instance = Instance.builder()
                .name("test-instance")
                .cpu(2)
                .memory(1024)
                .id("i-test")
                .state(InstanceState.RUNNING)
                .createdAt(createdAtInstant).build();

        Mockito.when(instanceStore.list())
                .thenReturn(
                        CompletableFuture.completedFuture(List.of(instance))
                );

        Mockito.when(computeBackend.describeStatuses(Mockito.any()))
                .thenReturn(Map.of(instance.getId(), ComputeStatus.RUNNING));
        app = new InstanceWebService(component).create();
    }

    @AfterEach
    void tearDown() {
        app.stop();
    }

    @Nested
    @Order(0)
    class CreateInstance {

        @BeforeEach void setup() {
            Mockito.when(computeBackend.create(ArgumentMatchers.any(Instance.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            Mockito.when(computeBackend.start(ArgumentMatchers.any(Instance.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            Mockito.when(instanceStore.create(ArgumentMatchers.any(Instance.class)))
                    .thenAnswer(invocation ->
                            CompletableFuture.completedFuture(invocation.getArgument(0, Instance.class)));
        }

        @Test
        void createsInstance() {
            JavalinTest.test(app, (server, client) -> {

                var response = client.post(
                        "/instances",
                        """
                                {
                                    "name": "test-instance",
                                    "cpu": 2,
                                    "memory": 1024
                                }
                                """
                );

                Assertions.assertThat(response.code())
                        .isEqualTo(200);

                Instance createdInstance =
                        new ObjectMapper().readValue(
                                response.body().string(),
                                Instance.class
                        );

                Assertions.assertThat(createdInstance.getId())
                        .startsWith("i-");

                Assertions.assertThat(createdInstance.getName())
                        .isEqualTo("test-instance");

                Assertions.assertThat(createdInstance.getCpu())
                        .isEqualTo(2);

                Assertions.assertThat(createdInstance.getMemory())
                        .isEqualTo(1024);

                Mockito.verify(computeBackend)
                        .create(ArgumentMatchers.any(Instance.class));

                Mockito.verify(computeBackend)
                        .start(ArgumentMatchers.any(Instance.class));
            });
        }
    }

    @Nested
    class ListInstances {

        @Test
        void listsInstances() {
            JavalinTest.test(app, (server, client) -> {

                var response = client.get("/instances");

                Assertions.assertThat(response.code())
                        .isEqualTo(200);

                ObjectReader reader =
                        new ObjectMapper()
                                .readerForListOf(Instance.class);

                List<Instance> instances =
                        reader.readValue(response.body().string());

                Assertions.assertThat(instances)
                        .hasSize(1);

                Assertions.assertThat(instances.getFirst().getName())
                        .isEqualTo("test-instance");

                Assertions.assertThat(instances.getFirst().getCpu())
                        .isEqualTo(2);

                Assertions.assertThat(instances.getFirst().getMemory())
                        .isEqualTo(1024);
            });
        }
    }
}