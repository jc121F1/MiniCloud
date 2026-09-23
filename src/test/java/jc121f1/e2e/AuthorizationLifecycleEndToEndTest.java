package jc121f1.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import jc121f1.dagger.AuthorizationCatalogModule;
import jc121f1.dagger.auth.AuthWebServiceComponent;
import jc121f1.dagger.authz.AuthzWebServiceComponent;
import jc121f1.dagger.instance.InstanceWebServiceComponent;
import jc121f1.model.auth.dao.Credential;
import jc121f1.services.auth.AuthServiceImpl;
import jc121f1.services.auth.store.nosql.DynamoDbAccountStore;
import jc121f1.services.auth.store.nosql.DynamoDbCredentialStore;
import jc121f1.services.auth.store.nosql.DynamoDbSessionStore;
import jc121f1.services.auth.store.nosql.DynamoDbUserStore;
import jc121f1.services.authz.AuthorizationRules;
import jc121f1.services.authz.AuthorizationServiceImpl;
import jc121f1.services.authz.PolicyServiceImpl;
import jc121f1.services.authz.PolicyValidator;
import jc121f1.services.authz.audit.AuditedAuthorizationService;
import jc121f1.services.authz.audit.AuditedPolicyService;
import jc121f1.services.authz.audit.AuthorizationAudit;
import jc121f1.services.authz.audit.AuthorizationAuditEvent;
import jc121f1.services.authz.store.nosql.DynamoDbPolicyStore;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.events.SimpleEventBus;
import jc121f1.services.instance.store.nosql.DynamoDbInstanceStore;
import jc121f1.model.instance.ComputeStatus;
import jc121f1.model.instance.dao.Instance;
import jc121f1.wbs.exceptions.MiniCloudExceptionMapper;
import jc121f1.wbs.handlers.RootHandler;
import jc121f1.wbs.handlers.auth.AuthAuthorizationHandler;
import jc121f1.wbs.handlers.auth.AuthenticateHandler;
import jc121f1.wbs.handlers.auth.CreateUserHandler;
import jc121f1.wbs.handlers.auth.DeleteUserHandler;
import jc121f1.wbs.handlers.auth.ExchangeServiceCredentialHandler;
import jc121f1.wbs.handlers.auth.GenerateCredentialHandler;
import jc121f1.wbs.handlers.auth.GetUserHandler;
import jc121f1.wbs.handlers.auth.InvalidateCredentialHandler;
import jc121f1.wbs.handlers.auth.LoginHandler;
import jc121f1.wbs.handlers.auth.TransferOwnershipHandler;
import jc121f1.wbs.handlers.authz.PolicyHandlers;
import jc121f1.wbs.handlers.instance.CreateInstanceHandler;
import jc121f1.wbs.handlers.instance.DeleteInstanceHandler;
import jc121f1.wbs.handlers.instance.GetInstanceHandler;
import jc121f1.wbs.handlers.instance.ListInstanceHandler;
import jc121f1.wbs.handlers.instance.StartInstanceHandler;
import jc121f1.wbs.handlers.instance.StopInstanceHandler;
import jc121f1.wbs.services.AuthWebService;
import jc121f1.wbs.services.AuthzWebService;
import jc121f1.wbs.services.InstanceWebService;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/** Real HTTP and isolated DynamoDB Local storage; compute never contacts Docker or other infrastructure. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DynamoDbLocalAvailable", matches = "True")
class AuthorizationLifecycleEndToEndTest {
    private final String suffix = UUID.randomUUID().toString();
    private final String accountTable = "LifecycleAccounts-" + suffix;
    private final String userTable = "LifecycleUsers-" + suffix;
    private final String credentialTable = "LifecycleCredentials-" + suffix;
    private final String sessionTable = "LifecycleSessions-" + suffix;
    private final String policyTable = "LifecyclePolicies-" + suffix;
    private final String instanceTable = "LifecycleInstances-" + suffix;
    private final ObjectMapper json = new ObjectMapper();
    private final List<AuthorizationAuditEvent> events = new CopyOnWriteArrayList<>();
    private final ControlledBackend backend = new ControlledBackend();
    private DynamoDbAsyncClient dynamo;
    private HttpClient http;
    private Javalin authApp;
    private Javalin authzApp;
    private Javalin instanceApp;
    private DynamoDbCredentialStore credentialStore;

    @BeforeAll
    void start() {
        dynamo = DynamoDbAsyncClient.builder().endpointOverride(URI.create("http://localhost:8000"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy"))).build();
        var users = new DynamoDbUserStore(dynamo, userTable);
        var accounts = new DynamoDbAccountStore(dynamo, users, accountTable);
        credentialStore = new DynamoDbCredentialStore(dynamo, credentialTable);
        var sessions = new DynamoDbSessionStore(dynamo, sessionTable);
        var policies = new DynamoDbPolicyStore(dynamo, policyTable);
        policies.initialize().join();
        var instances = new DynamoDbInstanceStore(dynamo, instanceTable);
        var registry = AuthorizationCatalogModule.actionRegistry();
        var validator = new PolicyValidator(registry);
        var audit = new AuthorizationAudit(Clock.systemUTC(), events::add);
        var authorization = new AuditedAuthorizationService(new AuthorizationServiceImpl(accounts, users, credentialStore,
                policies, registry, validator, new AuthorizationRules()), audit);
        var auth = new AuthServiceImpl(accounts, users, Clock.systemUTC(), sessions, credentialStore,
                new SecureRandom(), authorization, audit);
        var policyService = new AuditedPolicyService(new PolicyServiceImpl(authorization, policies, validator,
                users, credentialStore), audit);
        var instanceService = new InstanceServiceImpl(Clock.systemUTC(), backend,
                new SimpleEventBus(Runnable::run), instances, authorization);
        authApp = new AuthWebService(authComponent(auth)).create().start(0);
        authzApp = new AuthzWebService(authzComponent(auth, policyService)).create().start(0);
        instanceApp = new InstanceWebService(instanceComponent(auth, instanceService)).create().start(0);
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    void stop() {
        try {
            if (instanceApp != null) {
                instanceApp.stop();
            }
            if (authzApp != null) {
                authzApp.stop();
            }
            if (authApp != null) {
                authApp.stop();
            }
            if (http != null) {
                http.close();
            }
        } finally {
            if (dynamo != null) {
                RuntimeException cleanupFailure = null;
                for (String table : List.of(instanceTable, policyTable, sessionTable, credentialTable,
                        userTable, accountTable)) {
                    try {
                        dynamo.deleteTable(request -> request.tableName(table)).join();
                    } catch (RuntimeException error) {
                        if (!(root(error) instanceof ResourceNotFoundException)) {
                            if (cleanupFailure == null) {
                                cleanupFailure = error;
                            } else {
                                cleanupFailure.addSuppressed(error);
                            }
                        }
                    }
                }
                dynamo.close();
                if (cleanupFailure != null) {
                    throw cleanupFailure;
                }
            }
        }
    }

    @Test
    void complete_authorization_lifecycle() throws Exception {
        String password = "p-" + suffix;
        String ownerEmail = "owner-" + suffix + "@example.test";
        JsonNode owner = body(call(authApp, "POST", "/users/create",
                Map.of("userEmail", ownerEmail, "password", password), null), 200);
        String account = owner.path("accountId").asText();
        String ownerId = owner.path("userId").asText();
        String ownerToken = login(ownerEmail, password);
        String memberEmail = "member-" + suffix + "@example.test";
        JsonNode member = body(call(authApp, "POST", "/users/create",
                Map.of("userEmail", memberEmail, "password", password, "accountId", account), ownerToken), 200);
        String memberId = member.path("userId").asText();
        String memberToken = login(memberEmail, password);
        String instanceId = body(call(instanceApp, "POST", "/instances",
                Map.of("name", "instance-" + suffix, "cpu", 1, "memory", 1), ownerToken), 200).path("id").asText();
        Assertions.assertThat(backend.created).contains(instanceId);

        instanceDenied(instanceId, memberToken);
        status(call(instanceApp, "GET", "/instances", null, memberToken), 403);
        String userPolicy = createPolicy(ownerToken, document(
                statement("ALLOW", "instance:Describe", instanceResource(account, instanceId)),
                statement("ALLOW", "instance:List", accountResource(account))));
        attach(ownerToken, userPolicy, 1, account, memberId, "USER");
        instanceAllowed(instanceId, memberToken);
        Assertions.assertThat(body(call(instanceApp, "GET", "/instances", null, memberToken), 200).toString())
                .contains(instanceId);
        status(call(instanceApp, "POST", "/instances/start", Map.of("instanceId", instanceId), memberToken), 403);

        String otherEmail = "other-" + suffix + "@example.test";
        JsonNode other = body(call(authApp, "POST", "/users/create",
                Map.of("userEmail", otherEmail, "password", password), null), 200);
        String otherToken = login(otherEmail, password);
        String foreignId = body(call(instanceApp, "POST", "/instances",
                Map.of("name", "foreign-" + suffix, "cpu", 1, "memory", 1), otherToken), 200).path("id").asText();
        status(call(instanceApp, "POST", "/instances/describe", Map.of("instanceId", foreignId), memberToken), 403);
        Assertions.assertThat(body(call(instanceApp, "GET", "/instances", null, memberToken), 200).toString())
                .contains(instanceId).doesNotContain(foreignId);

        String generatePolicy = createPolicy(ownerToken, document(
                statement("ALLOW", "auth:GenerateCredential", "mc:auth:" + account + ":account/" + account)));
        attach(ownerToken, generatePolicy, 1, account, memberId, "USER");
        JsonNode issued = body(call(authApp, "POST", "/credentials/generate",
                Map.of("email", memberEmail, "password", password), null), 200);
        String credentialId = issued.path("credentialId").asText();
        String secret = issued.path("secret").asText();
        String credentialToken = body(call(authApp, "POST", "/credentials/exchange",
                Map.of("credentialId", credentialId, "secret", secret), null), 200).path("token").asText();
        Credential beforeRevocation = credentialStore.get(credentialId, true).join().orElseThrow();
        instanceDenied(instanceId, credentialToken);
        status(call(authApp, "POST", "/users/transfer-ownership",
                Map.of("newOwnerUserId", memberId), credentialToken), 403);
        String credentialPolicy = createPolicy(ownerToken, document(
                statement("ALLOW", "instance:Describe", instanceResource(account, instanceId))));
        attach(ownerToken, credentialPolicy, 1, account, credentialId, "CREDENTIAL");
        instanceAllowed(instanceId, credentialToken);

        detach(ownerToken, userPolicy, account, memberId, "USER");
        instanceDenied(instanceId, memberToken);
        instanceDenied(instanceId, credentialToken);
        status(call(instanceApp, "GET", "/instances", null, memberToken), 403);
        attach(ownerToken, userPolicy, 1, account, memberId, "USER");
        instanceAllowed(instanceId, credentialToken);
        body(call(authzApp, "POST", "/policies/update", Map.of("policyId", userPolicy, "expectedRevision", 1,
                "document", document(statement("ALLOW", "instance:List", accountResource(account)))), ownerToken), 200);
        instanceDenied(instanceId, memberToken);
        instanceDenied(instanceId, credentialToken);
        Assertions.assertThat(body(call(instanceApp, "GET", "/instances", null, memberToken), 200).toString())
                .doesNotContain(instanceId);
        body(call(authzApp, "POST", "/policies/update", Map.of("policyId", userPolicy, "expectedRevision", 2,
                "document", document(statement("ALLOW", "instance:Describe", instanceResource(account, instanceId)),
                        statement("ALLOW", "instance:List", accountResource(account)))), ownerToken), 200);
        instanceAllowed(instanceId, credentialToken);

        String denyPolicy = createPolicy(ownerToken, document(
                statement("DENY", "instance:Describe", instanceResource(account, instanceId))));
        attach(ownerToken, denyPolicy, 1, account, memberId, "USER");
        instanceDenied(instanceId, memberToken);
        instanceDenied(instanceId, credentialToken);
        detach(ownerToken, denyPolicy, account, memberId, "USER");
        instanceAllowed(instanceId, credentialToken);

        status(call(authApp, "POST", "/users/delete", Map.of("userId", ownerId), ownerToken), 403);
        status(call(authApp, "POST", "/credentials/invalidate", Map.of("credentialId", credentialId), ownerToken), 204);
        Assertions.assertThatThrownBy(() -> credentialStore.update(beforeRevocation,
                beforeRevocation.toBuilder().lastUsedAt(Instant.now().plusSeconds(60)).build()).join())
                .hasCauseInstanceOf(TransactionCanceledException.class);
        Assertions.assertThat(credentialStore.get(credentialId, true).join().orElseThrow().revoked()).isTrue();
        instanceDenied(instanceId, credentialToken);
        status(call(authApp, "POST", "/credentials/exchange",
                Map.of("credentialId", credentialId, "secret", secret), null), 401);
        JsonNode secondCredential = body(call(authApp, "POST", "/credentials/generate",
                Map.of("email", memberEmail, "password", password), null), 200);
        String secondSecret = secondCredential.path("secret").asText();
        String secondCredentialId = secondCredential.path("credentialId").asText();
        String secondToken = body(call(authApp, "POST", "/credentials/exchange",
                Map.of("credentialId", secondCredentialId, "secret", secondSecret), null), 200)
                .path("token").asText();
        attach(ownerToken, credentialPolicy, 1, account, secondCredentialId, "CREDENTIAL");
        instanceAllowed(instanceId, secondToken);
        status(call(authApp, "POST", "/users/delete", Map.of("userId", memberId), ownerToken), 200);
        instanceDenied(instanceId, memberToken);
        instanceDenied(instanceId, secondToken);

        String successorEmail = "successor-" + suffix + "@example.test";
        String successorId = body(call(authApp, "POST", "/users/create", Map.of("userEmail", successorEmail,
                "password", password, "accountId", account), ownerToken), 200).path("userId").asText();
        String successorToken = login(successorEmail, password);
        status(call(authzApp, "GET", "/policies", null, successorToken), 403);
        JsonNode ownerCredential = body(call(authApp, "POST", "/credentials/generate",
                Map.of("email", ownerEmail, "password", password), null), 200);
        String ownerCredentialId = ownerCredential.path("credentialId").asText();
        String ownerCredentialToken = body(call(authApp, "POST", "/credentials/exchange",
                Map.of("credentialId", ownerCredentialId, "secret", ownerCredential.path("secret").asText()), null), 200)
                .path("token").asText();
        attach(ownerToken, credentialPolicy, 1, account, ownerCredentialId, "CREDENTIAL");
        instanceAllowed(instanceId, ownerCredentialToken);
        String recoveryDeny = createPolicy(ownerToken, document(
                statement("DENY", "auth:TransferOwnership", "mc:auth:" + account + ":account/" + account),
                statement("DENY", "authz:*", "mc:authz:" + account + ":account/" + account)));
        attach(ownerToken, recoveryDeny, 1, account, successorId, "USER");
        status(call(authApp, "POST", "/users/transfer-ownership", Map.of("newOwnerUserId", successorId), ownerToken), 200);
        status(call(authzApp, "GET", "/policies", null, ownerToken), 403);
        status(call(authApp, "POST", "/users/transfer-ownership",
                Map.of("newOwnerUserId", ownerId), ownerToken), 403);
        instanceDenied(instanceId, ownerToken);
        instanceDenied(instanceId, ownerCredentialToken);
        instanceAllowed(instanceId, successorToken);
        status(call(authzApp, "GET", "/policies", null, successorToken), 200);
        status(call(authApp, "POST", "/users/delete", Map.of("userId", successorId), successorToken), 403);
        status(call(authApp, "POST", "/users/transfer-ownership", Map.of("newOwnerUserId", ownerId), successorToken), 200);
        Assertions.assertThat(events).anyMatch(event -> event.kind() == AuthorizationAuditEvent.Kind.IDENTITY_OPERATION
                && "auth:TransferOwnership".equals(event.action())
                && event.outcome() == AuthorizationAuditEvent.Outcome.SUCCESS);
        Assertions.assertThat(events).anyMatch(event -> event.kind() == AuthorizationAuditEvent.Kind.IDENTITY_OPERATION
                && "auth:TransferOwnership".equals(event.action())
                && event.outcome() == AuthorizationAuditEvent.Outcome.DENY);
        Assertions.assertThat(events).anyMatch(event -> event.kind() == AuthorizationAuditEvent.Kind.POLICY_OPERATION
                && event.outcome() == AuthorizationAuditEvent.Outcome.SUCCESS);
        Assertions.assertThat(events).anyMatch(event -> event.kind() == AuthorizationAuditEvent.Kind.DECISION
                && event.outcome() == AuthorizationAuditEvent.Outcome.DENY);
        Assertions.assertThat(events.toString()).doesNotContain(password, secret, secondSecret, ownerToken,
                credentialToken, secondToken,
                ownerCredential.path("secret").asText(), ownerCredentialToken);
        Assertions.assertThat(body(call(authApp, "POST", "/users/describe", Map.of("userId", ownerId), ownerToken), 200)
                .toString()).doesNotContain(password, secret, secondSecret, ownerToken, credentialToken, secondToken);
        Assertions.assertThat(other.path("accountId").asText()).isNotEqualTo(account);
    }

    private String login(String email, String password) throws Exception {
        Awaitility.await("new user email index to become visible").atMost(Duration.ofSeconds(10))
                .until(() -> call(authApp, "POST", "/users/login",
                        Map.of("email", email, "password", password), null).statusCode() == 200);
        return body(call(authApp, "POST", "/users/login", Map.of("email", email, "password", password), null), 200)
                .path("token").asText();
    }

    private String createPolicy(String token, Map<String, Object> document) throws Exception {
        return body(call(authzApp, "POST", "/policies/create", Map.of("document", document), token), 201)
                .path("policyId").asText();
    }

    private void attach(String token, String policyId, long revision, String account, String subject, String type)
            throws Exception {
        status(call(authzApp, "POST", "/policies/attach", Map.of("policyId", policyId,
                "expectedRevision", revision, "principal", principal(account, subject, type)), token), 204);
    }

    private void detach(String token, String policyId, String account, String subject, String type) throws Exception {
        status(call(authzApp, "POST", "/policies/detach", Map.of("policyId", policyId,
                "principal", principal(account, subject, type)), token), 204);
    }

    private void instanceAllowed(String id, String token) throws Exception {
        status(call(instanceApp, "POST", "/instances/describe", Map.of("instanceId", id), token), 200);
    }

    private void instanceDenied(String id, String token) throws Exception {
        HttpResponse<String> response = call(instanceApp, "POST", "/instances/describe", Map.of("instanceId", id), token);
        status(response, 403);
        Assertions.assertThat(response.body()).doesNotContain(token, "OWNER_REQUIRED", "NO_MATCHING_ALLOW");
    }

    private HttpResponse<String> call(Javalin app, String method, String path, Object payload, String token) throws Exception {
        String body = payload == null ? "" : json.writeValueAsString(payload);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response, int expected) throws Exception {
        status(response, expected);
        return json.readTree(response.body());
    }

    private static void status(HttpResponse<String> response, int expected) {
        Assertions.assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }

    private static Map<String, Object> principal(String account, String subject, String type) {
        return Map.of("accountId", account, "subjectId", subject, "subjectType", type);
    }

    private static Map<String, Object> document(Map<String, Object>... statements) {
        return Map.of("version", 1, "statements", List.of(statements));
    }

    private static Map<String, Object> statement(String effect, String action, String resource) {
        return Map.of("effect", effect, "actions", List.of(action), "resources", List.of(resource));
    }

    private static String instanceResource(String account, String instance) {
        return "mc:instance:" + account + ":instance/" + instance;
    }

    private static String accountResource(String account) {
        return "mc:instance:" + account + ":account/" + account;
    }

    private static Throwable root(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static AuthWebServiceComponent authComponent(AuthServiceImpl auth) {
        AuthWebServiceComponent component = Mockito.mock(AuthWebServiceComponent.class);
        Mockito.when(component.disableJmDNS()).thenReturn(true);
        Mockito.when(component.exceptionMapper()).thenReturn(new MiniCloudExceptionMapper());
        Mockito.when(component.rootHandler()).thenReturn(new RootHandler());
        var authenticate = new AuthenticateHandler(auth);
        Mockito.when(component.authAuthorizationHandler()).thenReturn(new AuthAuthorizationHandler(authenticate));
        Mockito.when(component.createUserHandler()).thenReturn(new CreateUserHandler(auth));
        Mockito.when(component.getUserHandler()).thenReturn(new GetUserHandler(auth));
        Mockito.when(component.deleteUserHandler()).thenReturn(new DeleteUserHandler(auth));
        Mockito.when(component.transferOwnershipHandler()).thenReturn(new TransferOwnershipHandler(auth));
        Mockito.when(component.loginHandler()).thenReturn(new LoginHandler(auth));
        Mockito.when(component.generateCredentialHandler()).thenReturn(new GenerateCredentialHandler(auth));
        Mockito.when(component.exchangeServiceCredentialHandler()).thenReturn(new ExchangeServiceCredentialHandler(auth));
        Mockito.when(component.invalidateCredentialHandler()).thenReturn(new InvalidateCredentialHandler(auth));
        return component;
    }

    private static AuthzWebServiceComponent authzComponent(AuthServiceImpl auth, AuditedPolicyService policy) {
        AuthzWebServiceComponent component = Mockito.mock(AuthzWebServiceComponent.class);
        Mockito.when(component.disableJmDNS()).thenReturn(true);
        Mockito.when(component.exceptionMapper()).thenReturn(new MiniCloudExceptionMapper());
        Mockito.when(component.authenticateHandler()).thenReturn(new AuthenticateHandler(auth));
        Mockito.when(component.policyHandlers()).thenReturn(new PolicyHandlers(policy));
        return component;
    }

    private static InstanceWebServiceComponent instanceComponent(AuthServiceImpl auth, InstanceServiceImpl service) {
        InstanceWebServiceComponent component = Mockito.mock(InstanceWebServiceComponent.class);
        Mockito.when(component.disableJmDNS()).thenReturn(true);
        Mockito.when(component.exceptionMapper()).thenReturn(new MiniCloudExceptionMapper());
        Mockito.when(component.rootHandler()).thenReturn(new RootHandler());
        Mockito.when(component.authenticateHandler()).thenReturn(new AuthenticateHandler(auth));
        Mockito.when(component.createInstanceHandler()).thenReturn(new CreateInstanceHandler(service));
        Mockito.when(component.getInstanceHandler()).thenReturn(new GetInstanceHandler(service));
        Mockito.when(component.listInstanceHandler()).thenReturn(new ListInstanceHandler(service));
        Mockito.when(component.startInstanceHandler()).thenReturn(new StartInstanceHandler(service));
        Mockito.when(component.stopInstanceHandler()).thenReturn(new StopInstanceHandler(service));
        Mockito.when(component.deleteInstanceHandler()).thenReturn(new DeleteInstanceHandler(service));
        return component;
    }

    private static final class ControlledBackend implements ComputeBackend {
        private final List<String> created = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Void> create(Instance instance) {
            created.add(instance.id());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> start(Instance instance) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> stop(Instance instance) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> delete(Instance instance) {
            created.remove(instance.id());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Map<String, ComputeStatus> describeStatuses(List<Instance> instances) {
            return Map.of();
        }

        @Override
        public void close() { }
    }
}
