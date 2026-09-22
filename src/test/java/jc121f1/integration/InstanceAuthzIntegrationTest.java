package jc121f1.integration;

import io.javalin.Javalin;
import jc121f1.dagger.AuthorizationCatalogModule;
import jc121f1.dagger.instance.InstanceWebServiceComponent;
import jc121f1.model.auth.AuthenticateRequest;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.auth.AuthService;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.authz.AuthorizationRules;
import jc121f1.services.authz.AuthorizationServiceImpl;
import jc121f1.services.authz.PolicyValidator;
import jc121f1.services.authz.audit.AuditedAuthorizationService;
import jc121f1.services.authz.audit.AuthorizationAudit;
import jc121f1.services.authz.audit.AuthorizationAuditEvent;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.store.PolicyStore;
import jc121f1.services.instance.InstanceServiceImpl;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.services.instance.events.EventBus;
import jc121f1.services.instance.exceptions.UnauthorizedException;
import jc121f1.services.instance.store.InstanceStore;
import jc121f1.wbs.exceptions.MiniCloudExceptionMapper;
import jc121f1.wbs.handlers.RootHandler;
import jc121f1.wbs.handlers.auth.AuthenticateHandler;
import jc121f1.wbs.handlers.instance.CreateInstanceHandler;
import jc121f1.wbs.handlers.instance.DeleteInstanceHandler;
import jc121f1.wbs.handlers.instance.GetInstanceHandler;
import jc121f1.wbs.handlers.instance.ListInstanceHandler;
import jc121f1.wbs.handlers.instance.StartInstanceHandler;
import jc121f1.wbs.handlers.instance.StopInstanceHandler;
import jc121f1.wbs.services.InstanceWebService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

class InstanceAuthzIntegrationTest {
    private static final String ACCOUNT = "a-1";
    private static final AuthenticatedSession MEMBER = new AuthenticatedSession(ACCOUNT, "u-member", Session.SubjectType.USER);
    private static final Instance FIRST = instance("i-1", "first", ACCOUNT);
    private static final Instance SECOND = instance("i-2", "second", ACCOUNT);
    private static final Instance FOREIGN = instance("i-3", "foreign", "a-2");

    private final AccountStore accounts = Mockito.mock(AccountStore.class);
    private final UserStore users = Mockito.mock(UserStore.class);
    private final CredentialStore credentials = Mockito.mock(CredentialStore.class);
    private final PolicyStore policies = Mockito.mock(PolicyStore.class);
    private final InstanceStore instances = Mockito.mock(InstanceStore.class);
    private final ComputeBackend backend = Mockito.mock(ComputeBackend.class);
    private final Map<PrincipalReference, List<Policy>> attachments = new ConcurrentHashMap<>();
    private final List<AuthorizationAuditEvent> auditEvents = new CopyOnWriteArrayList<>();
    private final AtomicReference<Credential> credential = new AtomicReference<>(Credential.builder()
            .credentialId("cre-1").accountId(ACCOUNT).createdByUserId("u-member").build());
    private InstanceServiceImpl service;
    private Javalin app;
    private HttpClient client;

    @BeforeEach
    void setup() {
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(Account.builder().accountId(ACCOUNT)
                .ownerId("u-owner").status(Account.AccountStatus.ACTIVE).build()));
        for (String id : List.of("u-owner", "u-member")) {
            Mockito.when(users.get(id, true)).thenReturn(found(User.builder().userId(id).accountId(ACCOUNT).build()));
        }
        Mockito.when(credentials.get("cre-1", true)).thenAnswer(call -> found(credential.get()));
        Mockito.when(policies.listAttached(Mockito.any())).thenAnswer(call ->
                CompletableFuture.completedFuture(attachments.getOrDefault(call.getArgument(0), List.of())));
        Mockito.when(instances.list()).thenReturn(CompletableFuture.completedFuture(List.of()));
        for (Instance instance : List.of(FIRST, SECOND, FOREIGN)) {
            Mockito.when(instances.get(instance.id())).thenReturn(found(instance));
        }
        Mockito.when(instances.update(Mockito.any(), Mockito.any())).thenAnswer(call -> foundItem(call.getArgument(1)));
        Mockito.when(instances.create(Mockito.any())).thenAnswer(call -> foundItem(call.getArgument(0)));
        Mockito.when(instances.delete(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(backend.create(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(backend.start(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(backend.stop(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(backend.delete(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));

        var registry = AuthorizationCatalogModule.actionRegistry();
        var evaluator = new AuthorizationServiceImpl(accounts, users, credentials, policies, registry,
                new PolicyValidator(registry), new AuthorizationRules());
        var audited = new AuditedAuthorizationService(evaluator,
                new AuthorizationAudit(Clock.systemUTC(), auditEvents::add));
        service = new InstanceServiceImpl(Clock.systemUTC(), backend, Mockito.mock(EventBus.class), instances, audited);
        Mockito.when(instances.list()).thenReturn(CompletableFuture.completedFuture(List.of(FIRST, SECOND, FOREIGN)));

        AuthService auth = Mockito.mock(AuthService.class);
        Mockito.when(auth.authenticate(Mockito.any())).thenAnswer(call -> caller(call.getArgument(0)));
        InstanceWebServiceComponent component = Mockito.mock(InstanceWebServiceComponent.class);
        Mockito.when(component.disableJmDNS()).thenReturn(true);
        Mockito.when(component.exceptionMapper()).thenReturn(new MiniCloudExceptionMapper());
        Mockito.when(component.authenticateHandler()).thenReturn(new AuthenticateHandler(auth));
        Mockito.when(component.rootHandler()).thenReturn(new RootHandler());
        Mockito.when(component.createInstanceHandler()).thenReturn(new CreateInstanceHandler(service));
        Mockito.when(component.listInstanceHandler()).thenReturn(new ListInstanceHandler(service));
        Mockito.when(component.getInstanceHandler()).thenReturn(new GetInstanceHandler(service));
        Mockito.when(component.startInstanceHandler()).thenReturn(new StartInstanceHandler(service));
        Mockito.when(component.stopInstanceHandler()).thenReturn(new StopInstanceHandler(service));
        Mockito.when(component.deleteInstanceHandler()).thenReturn(new DeleteInstanceHandler(service));
        app = new InstanceWebService(component).create().start(0);
        client = HttpClient.newHttpClient();
        Mockito.clearInvocations(instances, backend);
    }

    @AfterEach
    void teardown() {
        client.close();
        app.stop();
    }

    @Test
    void authenticationOwnerAccessAndStoredCrossAccountOwnership() throws Exception {
        Assertions.assertThat(send("POST", "/instances/start", idBody(FIRST), null).statusCode()).isEqualTo(401);
        Assertions.assertThat(send("POST", "/instances", createBody("a-spoofed"), "owner-token").statusCode())
                .isEqualTo(200);
        Mockito.verify(instances).create(Mockito.argThat(instance -> ACCOUNT.equals(instance.accountId())));
        Assertions.assertThat(send("POST", "/instances/describe", idBody(FIRST), "owner-token").statusCode())
                .isEqualTo(200);
        Assertions.assertThat(send("POST", "/instances/describe", idBody(FOREIGN), "owner-token").statusCode())
                .isEqualTo(403);
        Assertions.assertThat(send("POST", "/instances/start", idBody(FOREIGN), "owner-token").statusCode())
                .isEqualTo(403);
        Mockito.verify(backend, Mockito.never()).start(Mockito.argThat(instance ->
                FOREIGN.id().equals(instance.id())));
        Assertions.assertThat(auditEvents).anyMatch(event -> event.action().equals("instance:Describe")
                && event.outcome() == AuthorizationAuditEvent.Outcome.DENY);
    }

    @Test
    void policyChangesAffectServiceCallsWithoutRestart() throws Exception {
        Assertions.assertThatThrownBy(() -> service.start(MEMBER,
                StartInstanceRequest.builder().instanceId(FIRST.id()).build()))
                .isInstanceOf(AuthorizationDeniedException.class);
        Mockito.verify(backend, Mockito.never()).start(Mockito.any());

        attach("u-member", Session.SubjectType.USER, policy("p-allow", PolicyDocument.Effect.ALLOW,
                "instance:Start", resource(FIRST)));
        Assertions.assertThat(send("POST", "/instances/start", idBody(FIRST), "member-token").statusCode())
                .isEqualTo(200);
        Mockito.verify(backend).start(Mockito.any());

        attach("u-member", Session.SubjectType.USER, policy("p-allow", PolicyDocument.Effect.ALLOW,
                "instance:Start", resource(FIRST)), policy("p-deny", PolicyDocument.Effect.DENY,
                "instance:Start", resource(FIRST)));
        Assertions.assertThat(send("POST", "/instances/start", idBody(FIRST), "member-token").statusCode())
                .isEqualTo(403);
        Mockito.verify(backend, Mockito.times(1)).start(Mockito.any());
        attachments.clear();
        Assertions.assertThat(send("POST", "/instances/start", idBody(FIRST), "member-token").statusCode())
                .isEqualTo(403);
    }

    @Test
    void credentialNeedsItsOwnGrantAndTheCreatorsCurrentGrant() throws Exception {
        attach("u-member", Session.SubjectType.USER, policy("p-creator", PolicyDocument.Effect.ALLOW,
                "instance:Start", resource(FIRST)));
        Assertions.assertThat(send("POST", "/instances/start", idBody(FIRST), "credential-token").statusCode())
                .isEqualTo(403);
        attachments.remove(new PrincipalReference(ACCOUNT, "u-member", Session.SubjectType.USER));
        attach("cre-1", Session.SubjectType.CREDENTIAL, policy("p-credential", PolicyDocument.Effect.ALLOW,
                "instance:Start", resource(FIRST)));
        Assertions.assertThat(send("POST", "/instances/start", idBody(FIRST), "credential-token").statusCode())
                .isEqualTo(403);
        attach("u-member", Session.SubjectType.USER, policy("p-creator", PolicyDocument.Effect.ALLOW,
                "instance:Start", resource(FIRST)));
        Assertions.assertThat(send("POST", "/instances/start", idBody(FIRST), "credential-token").statusCode())
                .isEqualTo(200);
        attach("u-member", Session.SubjectType.USER, policy("p-creator-deny", PolicyDocument.Effect.DENY,
                "instance:Start", resource(FIRST)));
        Assertions.assertThat(send("POST", "/instances/start", idBody(FIRST), "credential-token").statusCode())
                .isEqualTo(403);
        credential.set(credential.get().toBuilder().revoked(true).build());
        Assertions.assertThat(send("POST", "/instances/start", idBody(FIRST), "credential-token").statusCode())
                .isEqualTo(403);
        Mockito.verify(backend, Mockito.times(1)).start(Mockito.any());
    }

    @Test
    void listNeedsAccountGrantAndPerInstanceDescribeGrants() throws Exception {
        attach("u-member", Session.SubjectType.USER, policy("p-list", PolicyDocument.Effect.ALLOW,
                "instance:List", "mc:instance:a-1:account/a-1"));
        Assertions.assertThat(send("GET", "/instances", "", "member-token").body()).isEqualTo("[]");
        attach("u-member", Session.SubjectType.USER, policy("p-list", PolicyDocument.Effect.ALLOW,
                "instance:List", "mc:instance:a-1:account/a-1"),
                policy("p-describe", PolicyDocument.Effect.ALLOW, "instance:Describe", "mc:instance:a-1:instance/*"),
                policy("p-deny-second", PolicyDocument.Effect.DENY, "instance:Describe", resource(SECOND)));
        var response = send("GET", "/instances", "", "member-token");
        Assertions.assertThat(response.statusCode()).isEqualTo(200);
        Assertions.assertThat(response.body()).contains(FIRST.id()).doesNotContain(SECOND.id(), FOREIGN.id());
    }

    @Test
    void policyStorageFailureAbortsBeforeBackendWork() throws Exception {
        Mockito.when(policies.listAttached(Mockito.any())).thenReturn(CompletableFuture.failedFuture(
                new AuthorizationStoreException("private storage failure", null)));
        var response = send("POST", "/instances/start", idBody(FIRST), "member-token");
        Assertions.assertThat(response.statusCode()).isEqualTo(500);
        Assertions.assertThat(response.body()).doesNotContain("private storage failure");
        Mockito.verify(backend, Mockito.never()).start(Mockito.any());
        Assertions.assertThat(auditEvents.getLast().outcome()).isEqualTo(AuthorizationAuditEvent.Outcome.ERROR);
    }

    private static AuthenticatedSession caller(AuthenticateRequest request) {
        if (request.bearerToken() == null) {
            throw new UnauthorizedException("Invalid credentials");
        }
        return switch (request.bearerToken()) {
            case "owner-token" -> new AuthenticatedSession(ACCOUNT, "u-owner", Session.SubjectType.USER);
            case "member-token" -> MEMBER;
            case "credential-token" -> new AuthenticatedSession(ACCOUNT, "cre-1", Session.SubjectType.CREDENTIAL);
            default -> throw new UnauthorizedException("Invalid credentials");
        };
    }

    private void attach(String subjectId, Session.SubjectType type, Policy... attached) {
        attachments.put(new PrincipalReference(ACCOUNT, subjectId, type), List.of(attached));
    }

    private static Policy policy(String id, PolicyDocument.Effect effect, String action, String resource) {
        return new Policy(id, ACCOUNT, 1, new PolicyDocument(1,
                List.of(new PolicyDocument.Statement(effect, List.of(action), List.of(resource)))));
    }

    private static String resource(Instance instance) {
        return "mc:instance:" + instance.accountId() + ":instance/" + instance.id();
    }

    private static String idBody(Instance instance) {
        return "{\"instanceId\":\"" + instance.id() + "\"}";
    }

    private static String createBody(String claimedAccount) {
        return "{\"name\":\"new\",\"cpu\":1,\"memory\":1,\"accountId\":\""
                + claimedAccount + "\"}";
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Instance instance(String id, String name, String accountId) {
        return Instance.builder().id(id).name(name).accountId(accountId).state(InstanceState.STOPPED).build();
    }

    private static <T> CompletableFuture<Optional<T>> found(T value) {
        return CompletableFuture.completedFuture(Optional.of(value));
    }

    private static <T> CompletableFuture<T> foundItem(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
