package jc121f1.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import jc121f1.annotations.MiniCloudTest;
import jc121f1.integration.testdagger.DaggerTestInstanceWebServiceComponent;
import jc121f1.integration.testdagger.TestInstanceWebServiceComponent;
import jc121f1.model.auth.AuthenticateRequest;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.authorization.InstanceAction;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.instance.exceptions.UnauthorizedException;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@MiniCloudTest
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class InstanceApiIntegrationTest {
    private static final AuthenticatedSession CALLER =
            new AuthenticatedSession("a-123", "u-123", Session.SubjectType.USER);

    private ComputeBackend computeBackend;
    private InstanceStore instanceStore;
    private AuthorizationService authorizationService;
    private Javalin app;
    private final Instant createdAtInstant = Instant.now();

    @BeforeEach
    void setUp() {
        TestInstanceWebServiceComponent component =
                DaggerTestInstanceWebServiceComponent.create();

        Mockito.when(component.authService().authenticate(Mockito.any(AuthenticateRequest.class)))
                .thenAnswer(invocation -> {
                    AuthenticateRequest request = invocation.getArgument(0);
                    if ("test-token".equals(request.bearerToken())) {
                        return CALLER;
                    }
                    throw new UnauthorizedException("Invalid credentials");
                });
        computeBackend = component.computeBackend();
        authorizationService = component.authorizationService();

        instanceStore = component.instanceStore();

        Instance instance = Instance.builder()
                .name("test-instance")
                .cpu(2)
                .memory(1024)
                .id("i-test")
                .accountId(CALLER.accountId())
                .state(InstanceState.RUNNING)
                .createdAt(createdAtInstant).build();

        Mockito.when(instanceStore.list())
                .thenReturn(
                        CompletableFuture.completedFuture(List.of(instance))
                );

        Mockito.when(computeBackend.describeStatuses(Mockito.any()))
                .thenReturn(Map.of(instance.id(), ComputeStatus.RUNNING));
        app = new InstanceWebService(component).create();
    }

    @AfterEach
    void tearDown() {
        app.stop();
    }

    @Nested
    @Order(0)
    class CreateInstance {
        @Test
        void createsInstance() {
            stubCreate();
            JavalinTest.test(app, (server, client) -> {

                var response = send("POST",
                        "/instances",
                        """
                                {
                                    "name": "test-instance",
                                    "cpu": 2,
                                    "memory": 1024
                                }
                                """
                );

                Assertions.assertThat(response.statusCode())
                        .isEqualTo(200);

                Instance createdInstance =
                        new ObjectMapper().readValue(
                                response.body(),
                                Instance.class
                        );

                Assertions.assertThat(createdInstance.id())
                        .startsWith("i-");

                Assertions.assertThat(createdInstance.name())
                        .isEqualTo("test-instance");

                Assertions.assertThat(createdInstance.cpu())
                        .isEqualTo(2);

                Assertions.assertThat(createdInstance.memory())
                        .isEqualTo(1024);

                Mockito.verify(computeBackend)
                        .create(ArgumentMatchers.any(Instance.class));

                Mockito.verify(computeBackend)
                        .start(ArgumentMatchers.any(Instance.class));
            });
        }

        @Test
        void rejectsMissingAuthenticationBeforeCreating() {
            JavalinTest.test(app, (server, client) -> {
                var response = send("POST", "/instances", "{\"name\":\"test-instance\"}", null);
                Assertions.assertThat(response.statusCode()).isEqualTo(401);
                Mockito.verify(instanceStore, Mockito.never()).create(Mockito.any());
            });
        }

        @Test
        void denialReturns403WithoutCreating() {
            JavalinTest.test(app, (server, client) -> {
                Mockito.doThrow(new AuthorizationDeniedException())
                        .when(authorizationService).authorize(Mockito.eq(CALLER),
                                Mockito.eq(InstanceAction.CREATE), Mockito.any());
                var response = send("POST", "/instances", "{\"name\":\"test-instance\"}");
                Assertions.assertThat(response.statusCode()).isEqualTo(403);
                Mockito.verify(instanceStore, Mockito.never()).create(Mockito.any());
                Mockito.verify(computeBackend, Mockito.never()).create(Mockito.any());
            });
        }

        @Test
        void clientCannotAssignAnotherAccountAsOwner() {
            stubCreate();
            JavalinTest.test(app, (server, client) -> {
                var response = send("POST", "/instances", """
                        {"name":"new-instance","cpu":2,"memory":1024,"accountId":"a-spoofed"}
                        """);
                Assertions.assertThat(response.statusCode()).isEqualTo(200);
                Mockito.verify(instanceStore).create(Mockito.argThat(instance ->
                        CALLER.accountId().equals(instance.accountId())));
            });
        }

        @Test
        void policyStorageFailureReturns500WithoutCreating() {
            JavalinTest.test(app, (server, client) -> {
                Mockito.doThrow(new AuthorizationStoreException("private failure", null))
                        .when(authorizationService).authorize(Mockito.eq(CALLER),
                                Mockito.eq(InstanceAction.CREATE), Mockito.any());
                var response = send("POST", "/instances", "{\"name\":\"test-instance\"}");
                Assertions.assertThat(response.statusCode()).isEqualTo(500);
                Assertions.assertThat(response.body()).doesNotContain("private failure");
                Mockito.verify(instanceStore, Mockito.never()).create(Mockito.any());
            });
        }
    }

    @Nested
    class ListInstances {

        @BeforeEach
        void allowDescribe() {
            Mockito.when(authorizationService.evaluate(Mockito.any(),
                            Mockito.any(InstanceAction.class), Mockito.any()))
                    .thenReturn(new AuthorizationDecision(AuthorizationDecision.Outcome.ALLOW,
                            AuthorizationDecision.Reason.POLICY_ALLOW, List.of()));
        }

        @Test
        void listsInstances() {
            JavalinTest.test(app, (server, client) -> {

                var response = send("GET", "/instances", "");

                Assertions.assertThat(response.statusCode())
                        .isEqualTo(200);

                ObjectReader reader =
                        new ObjectMapper()
                                .readerForListOf(Instance.class);

                List<Instance> instances =
                        reader.readValue(response.body());

                Assertions.assertThat(instances)
                        .hasSize(1);

                Assertions.assertThat(instances.getFirst().name())
                        .isEqualTo("test-instance");

                Assertions.assertThat(instances.getFirst().cpu())
                        .isEqualTo(2);

                Assertions.assertThat(instances.getFirst().memory())
                        .isEqualTo(1024);
            });
        }
    }

    private void stubCreate() {
        Mockito.when(computeBackend.create(ArgumentMatchers.any(Instance.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(computeBackend.start(ArgumentMatchers.any(Instance.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(instanceStore.create(ArgumentMatchers.any(Instance.class)))
                .thenAnswer(invocation ->
                        CompletableFuture.completedFuture(invocation.getArgument(0, Instance.class)));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return send(method, path, body, "test-token");
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
