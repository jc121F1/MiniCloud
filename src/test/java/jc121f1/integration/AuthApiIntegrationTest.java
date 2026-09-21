package jc121f1.integration;

import io.javalin.Javalin;
import jc121f1.dagger.auth.AuthWebServiceComponent;
import jc121f1.model.auth.AuthenticateRequest;
import jc121f1.model.auth.api.request.CreateUserRequest;
import jc121f1.model.auth.api.request.DeleteUserRequest;
import jc121f1.model.auth.api.request.ExchangeServiceCredentialRequest;
import jc121f1.model.auth.api.request.GenerateCredentialRequest;
import jc121f1.model.auth.api.request.GetUserRequest;
import jc121f1.model.auth.api.request.InvalidateCredentialRequest;
import jc121f1.model.auth.api.request.LoginRequest;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.PublicFacingCredential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.AuthService;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.instance.exceptions.UnauthorizedException;
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
import jc121f1.wbs.services.AuthWebService;
import lombok.SneakyThrows;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

class AuthApiIntegrationTest {
    private final AuthService service = Mockito.mock(AuthService.class);
    private final CredentialStore credentials = Mockito.mock(CredentialStore.class);
    private final User user = User.builder().userId("u-1").email("user@example.com")
            .accountId("a-1").passwordHash("private-password-hash").build();
    private Javalin app;
    private HttpClient client;

    @BeforeEach
    void setup() {
        AuthWebServiceComponent component = Mockito.mock(AuthWebServiceComponent.class);
        var authenticate = new AuthenticateHandler(service);
        Mockito.when(component.disableJmDNS()).thenReturn(true);
        Mockito.when(component.exceptionMapper()).thenReturn(new MiniCloudExceptionMapper());
        Mockito.when(component.rootHandler()).thenReturn(new RootHandler());
        Mockito.when(component.authAuthorizationHandler())
                .thenReturn(new AuthAuthorizationHandler(service, authenticate, credentials));
        Mockito.when(component.createUserHandler()).thenReturn(new CreateUserHandler(service));
        Mockito.when(component.getUserHandler()).thenReturn(new GetUserHandler(service));
        Mockito.when(component.deleteUserHandler()).thenReturn(new DeleteUserHandler(service));
        Mockito.when(component.loginHandler()).thenReturn(new LoginHandler(service));
        Mockito.when(component.generateCredentialHandler()).thenReturn(new GenerateCredentialHandler(service));
        Mockito.when(component.exchangeServiceCredentialHandler()).thenReturn(new ExchangeServiceCredentialHandler(service));
        Mockito.when(component.invalidateCredentialHandler()).thenReturn(new InvalidateCredentialHandler(service));
        Mockito.when(service.authenticate(Mockito.any())).thenAnswer(call -> {
            AuthenticateRequest request = call.getArgument(0);
            if (!"valid-token".equals(request.bearerToken())) {
                throw new UnauthorizedException("Invalid credentials");
            }
            return new AuthenticatedSession("a-1", "u-1", Session.SubjectType.USER);
        });
        app = new AuthWebService(component).create().start(0);
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
    void public_routes_parse_requests_and_return_results_without_existing_sessions() {
        Mockito.when(service.createUser(new CreateUserRequest("user@example.com", "password", null))).thenReturn(user);
        var created = post("/users/create", "{\"userEmail\":\"user@example.com\",\"password\":\"password\"}", false);
        Assertions.assertThat(created.statusCode()).isEqualTo(200);
        Assertions.assertThat(created.body()).contains("u-1").doesNotContain("passwordHash", "private-password-hash");

        Session session = Session.builder().token("issued-token").accountId("a-1").subjectType(Session.SubjectType.USER).build();
        Mockito.when(service.login(new LoginRequest("user@example.com", "password"))).thenReturn(session);
        var loggedIn = post("/users/login", "{\"email\":\"user@example.com\",\"password\":\"password\"}", false);
        Assertions.assertThat(loggedIn.statusCode()).isEqualTo(200);
        Assertions.assertThat(loggedIn.body()).contains("issued-token");

        Mockito.when(service.generateCredential(new GenerateCredentialRequest("user@example.com", "password")))
                .thenReturn(new PublicFacingCredential("cre-1", "a-1", "issued-secret"));
        var generated = post("/credentials/generate", "{\"email\":\"user@example.com\",\"password\":\"password\"}", false);
        Assertions.assertThat(generated.statusCode()).isEqualTo(200);
        Assertions.assertThat(generated.body()).contains("issued-secret");

        Mockito.when(service.exchangeServiceCredential(new ExchangeServiceCredentialRequest("cre-1", "secret"))).thenReturn(session);
        var exchanged = post("/credentials/exchange", "{\"credentialId\":\"cre-1\",\"secret\":\"secret\"}", false);
        Assertions.assertThat(exchanged.statusCode()).isEqualTo(200);
        Assertions.assertThat(exchanged.body()).contains("issued-token");
        Mockito.verify(service, Mockito.never()).authenticate(Mockito.any());
    }

    @Test
    void account_management_routes_accept_matching_identity_and_return_no_auth_context() {
        Mockito.when(service.getUser(new GetUserRequest(null, "u-1"))).thenReturn(user);
        Mockito.when(service.deleteUser(new DeleteUserRequest("u-1", null))).thenReturn(user);
        Mockito.when(service.createUser(new CreateUserRequest("member@example.com", "password", "a-1"))).thenReturn(user);
        var described = post("/users/describe", "{\"userId\":\"u-1\"}", true);
        Assertions.assertThat(described.statusCode()).isEqualTo(200);
        Assertions.assertThat(described.body()).doesNotContain("valid-token", "authenticatedSession", "private-password-hash");
        Assertions.assertThat(post("/users/delete", "{\"userId\":\"u-1\"}", true).statusCode()).isEqualTo(200);
        Assertions.assertThat(post("/users/create", "{\"userEmail\":\"member@example.com\",\"password\":\"password\",\"accountId\":\"a-1\"}", true)
                .statusCode()).isEqualTo(200);
        Mockito.when(credentials.get("cre-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(
                Credential.builder().credentialId("cre-1").accountId("a-1").build())));
        var invalidated = post("/credentials/invalidate", "{\"credentialId\":\"cre-1\"}", true);
        Assertions.assertThat(invalidated.statusCode()).isEqualTo(204);
        Assertions.assertThat(invalidated.body()).isEmpty();
        Mockito.verify(service).invalidateCredential(new InvalidateCredentialRequest("cre-1"));
    }

    @Test
    void protected_routes_reject_missing_authentication_before_accessing_resources() {
        for (String path : new String[] {"/users/describe", "/users/delete", "/credentials/invalidate"}) {
            Assertions.assertThat(post(path, "{}", false).statusCode()).isEqualTo(401);
        }
        Assertions.assertThat(post("/users/create", "{\"accountId\":\"a-1\"}", false).statusCode()).isEqualTo(401);
        Mockito.verify(service, Mockito.never()).getUser(Mockito.any());
        Mockito.verify(service, Mockito.never()).createUser(Mockito.any());
        Mockito.verify(service, Mockito.never()).deleteUser(Mockito.any());
        Mockito.verifyNoInteractions(credentials);
    }

    @Test
    void rejects_other_accounts_before_mutating_users_or_credentials() {
        Mockito.when(service.getUser(Mockito.any())).thenReturn(user.toBuilder().accountId("a-other").build());
        Mockito.when(credentials.get("cre-other")).thenReturn(CompletableFuture.completedFuture(Optional.of(
                Credential.builder().credentialId("cre-other").accountId("a-other").build())));
        Assertions.assertThat(post("/users/describe", "{\"userId\":\"u-other\"}", true).statusCode()).isEqualTo(401);
        Assertions.assertThat(post("/users/delete", "{\"userId\":\"u-other\"}", true).statusCode()).isEqualTo(401);
        Assertions.assertThat(post("/users/create", "{\"accountId\":\"a-other\"}", true).statusCode()).isEqualTo(401);
        Assertions.assertThat(post("/credentials/invalidate", "{\"credentialId\":\"cre-other\"}", true).statusCode()).isEqualTo(401);
        Mockito.verify(service, Mockito.never()).deleteUser(Mockito.any());
        Mockito.verify(service, Mockito.never()).createUser(Mockito.any());
        Mockito.verify(service, Mockito.never()).invalidateCredential(Mockito.any());
    }

    @Test
    void invalid_login_returns_401() {
        Mockito.when(service.login(Mockito.any())).thenThrow(new UnauthorizedException("Invalid credentials"));
        Assertions.assertThat(post("/users/login", "{\"email\":\"user@example.com\",\"password\":\"wrong\"}", false).statusCode())
                .isEqualTo(401);
    }

    @SneakyThrows
    private HttpResponse<String> post(String path, String body, boolean authenticated) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) {
            request.header("Authorization", "Bearer valid-token");
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
