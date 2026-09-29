package jc121f1.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import jc121f1.dagger.AuthorizationCatalogModule;
import jc121f1.dagger.authz.AuthzWebServiceComponent;
import jc121f1.model.auth.AuthenticateRequest;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.services.auth.AuthService;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.authz.AuthorizationRules;
import jc121f1.services.authz.AuthorizationServiceImpl;
import jc121f1.services.authz.PolicyServiceImpl;
import jc121f1.services.authz.PolicyValidator;
import jc121f1.services.authz.audit.AuditedAuthorizationService;
import jc121f1.services.authz.audit.AuditedPolicyService;
import jc121f1.services.authz.audit.AuthorizationAudit;
import jc121f1.services.authz.audit.AuthorizationAuditEvent;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import jc121f1.services.authz.exceptions.PolicyNotFoundException;
import jc121f1.services.authz.store.PolicyStore;
import jc121f1.services.instance.exceptions.UnauthorizedException;
import jc121f1.wbs.exceptions.MiniCloudExceptionMapper;
import jc121f1.wbs.handlers.auth.AuthenticateHandler;
import jc121f1.wbs.handlers.authz.PolicyHandlers;
import jc121f1.wbs.services.AuthzWebService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

class AuthzApiIntegrationTest {
    private static final String ACCOUNT = "a-1";
    private static final PrincipalReference TARGET = new PrincipalReference(ACCOUNT, "u-member", Session.SubjectType.USER);
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AuthorizationAuditEvent> events = new CopyOnWriteArrayList<>();
    private final PolicyStore policies = Mockito.mock(PolicyStore.class);
    private final AccountStore accounts = Mockito.mock(AccountStore.class);
    private final UserStore users = Mockito.mock(UserStore.class);
    private final CredentialStore credentials = Mockito.mock(CredentialStore.class);
    private Javalin app;
    private HttpClient client;

    @BeforeEach
    void setup() {
        AuthService auth = Mockito.mock(AuthService.class);
        Mockito.when(auth.authenticate(Mockito.any())).thenAnswer(call -> {
            AuthenticateRequest request = call.getArgument(0);
            if ("owner-token".equals(request.bearerToken())) {
                return new AuthenticatedSession(ACCOUNT, "u-owner", Session.SubjectType.USER);
            }
            if ("member-token".equals(request.bearerToken())) {
                return new AuthenticatedSession(ACCOUNT, TARGET.subjectId(), Session.SubjectType.USER);
            }
            if ("credential-token".equals(request.bearerToken())) {
                return new AuthenticatedSession(ACCOUNT, "cre-1", Session.SubjectType.CREDENTIAL);
            }
            throw new UnauthorizedException("Invalid credentials");
        });
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account()));
        for (String id : List.of("u-owner", TARGET.subjectId())) {
            Mockito.when(users.get(id, true)).thenReturn(found(User.builder().userId(id).accountId(ACCOUNT).build()));
        }
        Mockito.when(credentials.get("cre-1", true)).thenReturn(found(Credential.builder().credentialId("cre-1")
                .accountId(ACCOUNT).createdByUserId("u-owner").revoked(false).build()));
        var registry = AuthorizationCatalogModule.actionRegistry();
        var validator = new PolicyValidator(registry);
        var audit = new AuthorizationAudit(Clock.systemUTC(), events::add);
        var evaluator = new AuditedAuthorizationService(new AuthorizationServiceImpl(accounts, users, credentials, policies,
                registry, validator, new AuthorizationRules()), audit);
        var management = new AuditedPolicyService(new PolicyServiceImpl(evaluator, policies, validator, users, credentials), audit);
        AuthzWebServiceComponent component = Mockito.mock(AuthzWebServiceComponent.class);
        Mockito.when(component.disableJmDNS()).thenReturn(true);
        Mockito.when(component.exceptionMapper()).thenReturn(new MiniCloudExceptionMapper());
        Mockito.when(component.authenticateHandler()).thenReturn(new AuthenticateHandler(auth));
        Mockito.when(component.policyHandlers()).thenReturn(new PolicyHandlers(management));
        app = new AuthzWebService(component).create().start(0);
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void teardown() {
        if (client != null) {
            client.close();
        }
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void owner_routes_round_trip_documents_revisions_and_attachment_targets() throws Exception {
        Mockito.when(policies.create(Mockito.any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
        var response = send("POST", "/policies/create", json(Map.of("document", document())), "owner-token");
        Assertions.assertThat(response.statusCode()).isEqualTo(201);
        Policy created = mapper.readValue(response.body(), Policy.class);
        Assertions.assertThat(created.accountId()).isEqualTo(ACCOUNT);
        Assertions.assertThat(created.revision()).isEqualTo(1);
        Assertions.assertThat(created.document()).isEqualTo(document());
        String id = created.policyId();
        Mockito.when(policies.get(ACCOUNT, id)).thenReturn(found(created));
        Mockito.when(policies.list(ACCOUNT)).thenReturn(CompletableFuture.completedFuture(List.of(created)));
        Assertions.assertThat(send("POST", "/policies/describe", json(Map.of("policyId", id)), "owner-token").body())
                .isEqualTo(response.body());
        var listed = send("GET", "/policies", "", "owner-token");
        Assertions.assertThat(listed.statusCode()).isEqualTo(200);
        Assertions.assertThat(mapper.readValue(listed.body(), Policy[].class)).containsExactly(created);

        Policy updated = new Policy(id, ACCOUNT, 2, document());
        Mockito.when(policies.update(ACCOUNT, id, 1, document())).thenReturn(CompletableFuture.completedFuture(updated));
        var replaced = send("POST", "/policies/update", json(Map.of("policyId", id, "expectedRevision", 1, "document", document())), "owner-token");
        Assertions.assertThat(replaced.statusCode()).isEqualTo(200);
        Assertions.assertThat(mapper.readValue(replaced.body(), Policy.class)).isEqualTo(updated);
        Mockito.when(policies.attach(ACCOUNT, id, 2, TARGET)).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(policies.listAttached(TARGET)).thenReturn(CompletableFuture.completedFuture(List.of(updated)));
        Mockito.when(policies.detach(ACCOUNT, id, TARGET)).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(policies.delete(ACCOUNT, id, 2)).thenReturn(CompletableFuture.completedFuture(null));
        assertNoContent(send("POST", "/policies/attach", json(Map.of("policyId", id, "expectedRevision", 2, "principal", TARGET)), "owner-token"));
        var attached = send("POST", "/policies/attachments/list", json(Map.of("principal", TARGET)), "owner-token");
        Assertions.assertThat(attached.statusCode()).isEqualTo(200);
        Assertions.assertThat(mapper.readValue(attached.body(), Policy[].class)).containsExactly(updated);
        assertNoContent(send("POST", "/policies/detach", json(Map.of("policyId", id, "principal", TARGET)), "owner-token"));
        assertNoContent(send("POST", "/policies/delete", json(Map.of("policyId", id, "expectedRevision", 2)), "owner-token"));
        Mockito.verify(policies).attach(ACCOUNT, id, 2, TARGET);
        Mockito.verify(policies).detach(ACCOUNT, id, TARGET);
        Mockito.verify(policies).delete(ACCOUNT, id, 2);
        Assertions.assertThat(events.stream().filter(event -> event.kind() == AuthorizationAuditEvent.Kind.POLICY_OPERATION))
                .hasSize(8).allMatch(event -> event.outcome() == AuthorizationAuditEvent.Outcome.SUCCESS);
        Assertions.assertThat(response.body()).doesNotContain("owner-token", "authenticatedSession", "passwordHash");
    }

    @Test
    void every_route_authenticates_before_parsing_or_accessing_storage() throws Exception {
        for (ApiCall call : calls()) {
            Assertions.assertThat(send(call.method(), call.path(), "invalid JSON", null).statusCode()).isEqualTo(401);
            Assertions.assertThat(send(call.method(), call.path(), call.body(), "invalid-token").statusCode()).isEqualTo(401);
        }
        Mockito.verifyNoInteractions(policies, accounts, users, credentials);
    }

    @Test
    void every_route_denies_members_and_owner_credentials_without_policy_access() throws Exception {
        for (String token : List.of("member-token", "credential-token")) {
            for (ApiCall call : calls()) {
                var response = send(call.method(), call.path(), call.body(), token);
                Assertions.assertThat(response.statusCode()).as(call.path()).isEqualTo(403);
                Assertions.assertThat(response.body()).doesNotContain("OWNER_REQUIRED", "CREDENTIAL_OPERATION", token);
            }
        }
        Mockito.verifyNoInteractions(policies);
        Assertions.assertThat(events).noneMatch(event -> event.outcome() == AuthorizationAuditEvent.Outcome.SUCCESS);
    }

    @Test
    void rejects_cross_account_targets_and_client_supplied_caller_identity() throws Exception {
        PrincipalReference foreign = new PrincipalReference("a-other", TARGET.subjectId(), TARGET.subjectType());
        for (String path : List.of("/policies/attach", "/policies/detach", "/policies/attachments/list")) {
            Map<String, Object> body = switch (path) {
                case "/policies/attach" -> Map.of("policyId", "p-1", "expectedRevision", 1, "principal", foreign);
                case "/policies/detach" -> Map.of("policyId", "p-1", "principal", foreign);
                default -> Map.of("principal", foreign);
            };
            Assertions.assertThat(send("POST", path, json(body), "owner-token").statusCode()).isEqualTo(403);
        }
        Assertions.assertThat(send("POST", "/policies/create", json(Map.of("document", document(), "accountId", "a-other")), "owner-token")
                .statusCode()).isEqualTo(400);
        Assertions.assertThat(send("POST", "/policies/delete", json(Map.of("policyId", "p-1", "expectedRevision", 1,
                "caller", Map.of("accountId", ACCOUNT, "subjectId", "u-owner", "subjectType", "USER"))), "member-token")
                .statusCode()).isEqualTo(400);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void invalid_json_missing_fields_invalid_documents_and_large_bodies_never_mutate() throws Exception {
        for (String body : List.of("{", "null", "{}", "{\"document\":null}",
                "{\"document\":{\"version\":1,\"statements\":null}}", json(Map.of("document", document())) + " {}",
                "{\"document\":null,\"document\":" + json(document()) + "}",
                json(Map.of("document", new PolicyDocument(2, document().statements()))))) {
            Assertions.assertThat(send("POST", "/policies/create", body, "owner-token").statusCode()).as(body).isEqualTo(400);
        }
        for (ApiCall call : calls().stream().filter(call -> call.method().equals("POST")).toList()) {
            Assertions.assertThat(send(call.method(), call.path(), "{}", "owner-token").statusCode()).isEqualTo(400);
        }
        Assertions.assertThat(send("POST", "/policies/delete", "{\"policyId\":\"p-1\"}", "owner-token").statusCode()).isEqualTo(400);
        Assertions.assertThat(send("POST", "/policies/delete", "{\"policyId\":\"p-1\",\"expectedRevision\":1.5}", "owner-token")
                .statusCode()).isEqualTo(400);
        Assertions.assertThat(send("POST", "/policies/create", " ".repeat(65537), "owner-token").statusCode()).isEqualTo(413);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void inactive_accounts_and_deleted_or_revoked_identities_cannot_manage_policies() throws Exception {
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account().toBuilder().status(Account.AccountStatus.SUSPENDED).build()));
        Assertions.assertThat(send("GET", "/policies", "", "owner-token").statusCode()).isEqualTo(403);
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account()));
        Mockito.when(users.get("u-owner", true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Assertions.assertThat(send("GET", "/policies", "", "owner-token").statusCode()).isEqualTo(403);
        Mockito.when(credentials.get("cre-1", true)).thenReturn(found(Credential.builder().credentialId("cre-1")
                .accountId(ACCOUNT).createdByUserId("u-owner").revoked(true).build()));
        Assertions.assertThat(send("GET", "/policies", "", "credential-token").statusCode()).isEqualTo(403);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void body_limit_also_applies_to_chunked_multibyte_requests() throws Exception {
        byte[] oversized = "é".repeat(33000).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/policies/create"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer owner-token")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized))).build();
        Assertions.assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void missing_attachment_targets_are_rejected_but_detach_can_clean_them_up() throws Exception {
        Mockito.when(users.get(TARGET.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        String attach = json(Map.of("policyId", "p-1", "expectedRevision", 1, "principal", TARGET));
        Assertions.assertThat(send("POST", "/policies/attach", attach, "owner-token").statusCode()).isEqualTo(404);
        Mockito.verifyNoInteractions(policies);
        Mockito.when(policies.detach(ACCOUNT, "p-1", TARGET)).thenReturn(CompletableFuture.completedFuture(null));
        assertNoContent(send("POST", "/policies/detach", json(Map.of("policyId", "p-1", "principal", TARGET)), "owner-token"));
        Mockito.verify(policies).detach(ACCOUNT, "p-1", TARGET);
    }

    @Test
    void maps_missing_conflicting_and_unavailable_storage_without_leaking_details() throws Exception {
        Mockito.when(policies.get(ACCOUNT, "p-1")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Assertions.assertThat(send("POST", "/policies/describe", "{\"policyId\":\"p-1\"}", "owner-token").statusCode()).isEqualTo(404);
        Mockito.when(policies.delete(ACCOUNT, "p-1", 1)).thenReturn(CompletableFuture.failedFuture(new PolicyConflictException("Attached policy")));
        Assertions.assertThat(send("POST", "/policies/delete", "{\"policyId\":\"p-1\",\"expectedRevision\":1}", "owner-token")
                .statusCode()).isEqualTo(409);
        Mockito.when(policies.detach(ACCOUNT, "p-1", TARGET)).thenReturn(CompletableFuture.failedFuture(new PolicyNotFoundException("Missing")));
        Assertions.assertThat(send("POST", "/policies/detach", json(Map.of("policyId", "p-1", "principal", TARGET)), "owner-token")
                .statusCode()).isEqualTo(404);
        Mockito.when(policies.list(ACCOUNT)).thenReturn(CompletableFuture.failedFuture(new AuthorizationStoreException("private-db-secret", null)));
        var unavailable = send("GET", "/policies", "", "owner-token");
        Assertions.assertThat(unavailable.statusCode()).isEqualTo(500);
        Assertions.assertThat(unavailable.body()).doesNotContain("private-db-secret", "AuthorizationStoreException", "stackTrace");
        Assertions.assertThat(events.getLast().reason()).isEqualTo("STORAGE_FAILURE");
    }

    private List<ApiCall> calls() throws Exception {
        return List.of(new ApiCall("GET", "/policies", ""),
                new ApiCall("POST", "/policies/create", json(Map.of("document", document()))),
                new ApiCall("POST", "/policies/describe", json(Map.of("policyId", "p-1"))),
                new ApiCall("POST", "/policies/update", json(Map.of("policyId", "p-1", "expectedRevision", 1, "document", document()))),
                new ApiCall("POST", "/policies/delete", json(Map.of("policyId", "p-1", "expectedRevision", 1))),
                new ApiCall("POST", "/policies/attach", json(Map.of("policyId", "p-1", "expectedRevision", 1, "principal", TARGET))),
                new ApiCall("POST", "/policies/detach", json(Map.of("policyId", "p-1", "principal", TARGET))),
                new ApiCall("POST", "/policies/attachments/list", json(Map.of("principal", TARGET))));
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static void assertNoContent(HttpResponse<String> response) {
        Assertions.assertThat(response.statusCode()).isEqualTo(204);
        Assertions.assertThat(response.body()).isEmpty();
    }

    private static Account account() {
        return Account.builder().accountId(ACCOUNT).ownerId("u-owner").status(Account.AccountStatus.ACTIVE).build();
    }

    private static PolicyDocument document() {
        return new PolicyDocument(1, List.of(new PolicyDocument.Statement(PolicyDocument.Effect.ALLOW,
                List.of("instance:Start"), List.of("mc:instance:a-1:instance/*"))));
    }

    private static <T> CompletableFuture<Optional<T>> found(T value) {
        return CompletableFuture.completedFuture(Optional.of(value));
    }

    private record ApiCall(String method, String path, String body) { }
}
