package jc121f1.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import jc121f1.annotations.MiniCloudTest;
import jc121f1.dagger.WebserviceHandlers;
import jc121f1.integration.testdagger.DaggerTestWebserviceComponent;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.wbs.WebService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@MiniCloudTest
class InstanceApiIntegrationTest {

    private ComputeBackend computeBackend;
    private Javalin app;

    @BeforeEach
    void setUp() {
        WebserviceHandlers component =
                DaggerTestWebserviceComponent.create();

        computeBackend = component.computeBackend();

        Mockito.when(computeBackend.create(ArgumentMatchers.any(Instance.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        Mockito.when(computeBackend.start(ArgumentMatchers.any(Instance.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        app = WebService.create(component);
    }

    @AfterEach
    void tearDown() {
        app.stop();
    }

    @Nested
    class CreateInstance {

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

                Instance instance =
                        new ObjectMapper().readValue(
                                response.body().string(),
                                Instance.class
                        );

                Assertions.assertThat(instance.getId())
                        .startsWith("i-");

                Assertions.assertThat(instance.getName())
                        .isEqualTo("test-instance");

                Assertions.assertThat(instance.getCpu())
                        .isEqualTo(2);

                Assertions.assertThat(instance.getMemory())
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

                client.post(
                        "/instances",
                        """
                                {
                                    "name": "test-instance",
                                    "cpu": 2,
                                    "memory": 1024
                                }
                                """
                );

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