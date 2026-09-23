package jc121f1.service.auth;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.common.PasswordUtil;
import jc121f1.model.auth.api.request.CreateUserRequest;
import jc121f1.model.auth.api.request.DeleteUserRequest;
import jc121f1.model.auth.api.request.GenerateCredentialRequest;
import jc121f1.model.auth.api.request.GetUserRequest;
import jc121f1.model.auth.api.request.InvalidateCredentialRequest;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.model.authz.ResourceReference;
import jc121f1.model.authz.ServiceId;
import jc121f1.services.auth.AuthService;
import jc121f1.services.auth.AuthServiceImpl;
import jc121f1.services.auth.authorization.AuthAction;
import jc121f1.services.auth.authorization.AuthResourceType;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.SessionStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.instance.exceptions.UnauthorizedException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@MiniCloudTest
class AuthServiceAuthorizationTest {
    private static final String PASSWORD = "correct password";
    private static final AuthenticatedSession CALLER =
            new AuthenticatedSession("a-1", "u-actor", Session.SubjectType.USER);
    private static final User TARGET = User.builder().userId("u-target").email("target@example.com")
            .accountId("a-2").passwordHash(PasswordUtil.hash(PASSWORD.toCharArray())).build();
    @Mock private AccountStore accounts;
    @Mock private UserStore users;
    @Mock private CredentialStore credentials;
    @Mock private SessionStore sessions;
    @Mock private AuthorizationService authorization;
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(accounts, users, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC), sessions, credentials, new SecureRandom(), authorization);
    }

    @Test
    void target_ownership_and_action_are_resolved_before_returning_or_deleting_a_user() {
        Mockito.when(users.get("u-target")).thenReturn(CompletableFuture.completedFuture(Optional.of(TARGET)));
        ResourceReference resource = resource("a-2", AuthResourceType.USER, "u-target");
        Mockito.doThrow(new AuthorizationDeniedException()).when(authorization)
                .authorize(CALLER, AuthAction.DESCRIBE_USER, resource);
        Mockito.doThrow(new AuthorizationDeniedException()).when(authorization)
                .authorize(CALLER, AuthAction.DELETE_USER, resource);

        Assertions.assertThatThrownBy(() -> service.getUser(CALLER, new GetUserRequest(null, "u-target")))
                .isInstanceOf(AuthorizationDeniedException.class);
        Assertions.assertThatThrownBy(() -> service.deleteUser(CALLER, new DeleteUserRequest("u-target", null)))
                .isInstanceOf(AuthorizationDeniedException.class);
        Mockito.verify(users, Mockito.never()).delete(Mockito.any());
    }

    @Test
    void denied_existing_account_creation_never_writes_an_identity() {
        Mockito.doThrow(new AuthorizationDeniedException()).when(authorization).authorize(CALLER,
                AuthAction.CREATE_USER, resource("a-2", AuthResourceType.ACCOUNT, "a-2"));

        Assertions.assertThatThrownBy(() -> service.createUser(CALLER,
                new CreateUserRequest("new@example.com", PASSWORD, "a-2")))
                .isInstanceOf(AuthorizationDeniedException.class);
        Mockito.verifyNoInteractions(users);
        Mockito.verifyNoInteractions(accounts);
    }

    @Test
    void credential_target_ownership_is_checked_before_revocation() {
        Credential credential = Credential.builder().credentialId("cre-1").accountId("a-2").build();
        Mockito.when(credentials.get("cre-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(credential)));
        Mockito.doThrow(new AuthorizationDeniedException()).when(authorization).authorize(CALLER,
                AuthAction.INVALIDATE_CREDENTIAL, resource("a-2", AuthResourceType.CREDENTIAL, "cre-1"));

        Assertions.assertThatThrownBy(() -> service.invalidateCredential(CALLER,
                new InvalidateCredentialRequest("cre-1"))).isInstanceOf(AuthorizationDeniedException.class);
        Mockito.verify(credentials, Mockito.never()).update(Mockito.any(), Mockito.any());
    }

    @Test
    void password_identity_controls_credential_generation_and_denial_leaves_storage_unchanged() {
        Mockito.when(users.findByEmail(TARGET.email())).thenReturn(CompletableFuture.completedFuture(TARGET));
        Mockito.when(accounts.get("a-2")).thenReturn(CompletableFuture.completedFuture(Optional.of(
                Account.builder().accountId("a-2").status(Account.AccountStatus.ACTIVE).build())));
        AuthenticatedSession passwordIdentity = new AuthenticatedSession("a-2", "u-target", Session.SubjectType.USER);
        Mockito.doThrow(new AuthorizationDeniedException()).when(authorization).authorize(passwordIdentity,
                AuthAction.GENERATE_CREDENTIAL, resource("a-2", AuthResourceType.ACCOUNT, "a-2"));

        Assertions.assertThatThrownBy(() -> service.generateCredential(
                new GenerateCredentialRequest(TARGET.email(), PASSWORD)))
                .isInstanceOf(AuthorizationDeniedException.class);
        Mockito.verify(credentials, Mockito.never()).create(Mockito.any());
        Assertions.assertThatThrownBy(() -> service.generateCredential(
                new GenerateCredentialRequest(TARGET.email(), "wrong")))
                .isInstanceOf(UnauthorizedException.class);
        Mockito.verifyNoInteractions(sessions);
    }

    @Test
    void signup_cannot_be_used_to_add_an_account_member() {
        Assertions.assertThatThrownBy(() -> service.createUser(
                new CreateUserRequest("new@example.com", PASSWORD, "a-1")))
                .isInstanceOf(UnauthorizedException.class);
        Mockito.verifyNoInteractions(accounts, users, authorization);
    }

    @Test
    void authorization_storage_failure_aborts_deletion() {
        Mockito.when(users.get("u-target")).thenReturn(CompletableFuture.completedFuture(Optional.of(TARGET)));
        Mockito.doThrow(new AuthorizationStoreException("policy store unavailable", new IllegalStateException()))
                .when(authorization)
                .authorize(CALLER, AuthAction.DELETE_USER,
                        resource("a-2", AuthResourceType.USER, "u-target"));

        Assertions.assertThatThrownBy(() -> service.deleteUser(CALLER,
                new DeleteUserRequest("u-target", null))).isInstanceOf(AuthorizationStoreException.class);
        Mockito.verify(users, Mockito.never()).delete(Mockito.any());
    }

    private static ResourceReference resource(String account, AuthResourceType type, String id) {
        return ResourceReference.of(ServiceId.AUTH, account, type, id);
    }
}
