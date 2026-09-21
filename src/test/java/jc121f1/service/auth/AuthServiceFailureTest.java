package jc121f1.service.auth;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.common.PasswordUtil;
import jc121f1.model.auth.api.request.CreateUserRequest;
import jc121f1.model.auth.api.request.DeleteUserRequest;
import jc121f1.model.auth.api.request.ExchangeServiceCredentialRequest;
import jc121f1.model.auth.api.request.GenerateCredentialRequest;
import jc121f1.model.auth.api.request.GetUserRequest;
import jc121f1.model.auth.api.request.InvalidateCredentialRequest;
import jc121f1.model.auth.api.request.LoginRequest;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.AuthService;
import jc121f1.services.auth.AuthServiceImpl;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.SessionStore;
import jc121f1.services.auth.store.UserStore;
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
import java.util.concurrent.CompletionException;

@MiniCloudTest
class AuthServiceFailureTest {
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "test password";
    private static final String HASH = PasswordUtil.hash(PASSWORD.toCharArray());
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final User USER = User.builder().userId("u-1").accountId("a-1")
            .email(EMAIL).passwordHash(HASH).build();
    private static final Credential CREDENTIAL = Credential.builder().credentialId("cre-1").accountId("a-1")
            .secretHash(HASH).createdAt(NOW.minusSeconds(60)).build();

    @Mock private AccountStore accounts;
    @Mock private UserStore users;
    @Mock private CredentialStore credentials;
    @Mock private SessionStore sessions;
    @Mock private SecureRandom random;
    private AuthService service;
    private final RuntimeException failure = new IllegalStateException("store unavailable");

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(accounts, users, Clock.fixed(NOW, ZoneOffset.UTC), sessions, credentials, random);
    }

    private void activeAccount() {
        Mockito.when(accounts.get("a-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(
                Account.builder().accountId("a-1").status(Account.AccountStatus.ACTIVE).build())));
    }

    private void authenticateUser() {
        Mockito.when(users.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(USER));
        activeAccount();
    }

    private void authenticateCredential() {
        Mockito.when(credentials.get("cre-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(CREDENTIAL)));
        activeAccount();
    }

    @Test
    void failed_account_creation_never_creates_a_user_or_attempts_rollback() {
        Mockito.when(accounts.create(Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.createUser(new CreateUserRequest(EMAIL, PASSWORD, null)))
                .isInstanceOf(CompletionException.class).hasCause(failure);
        Mockito.verifyNoInteractions(users);
        Mockito.verify(accounts, Mockito.never()).delete(Mockito.any());
    }

    @Test
    void failed_account_lookup_never_creates_a_user() {
        Mockito.when(accounts.get("a-1")).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.createUser(new CreateUserRequest(EMAIL, PASSWORD, "a-1")))
                .hasCause(failure);
        Mockito.verifyNoInteractions(users);
        Mockito.verify(accounts, Mockito.never()).delete(Mockito.any());
    }

    @Test
    void failed_user_lookup_does_not_fall_back_to_email_or_delete() {
        Mockito.when(users.get("u-1")).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.getUser(new GetUserRequest(null, "u-1"))).hasCause(failure);
        Assertions.assertThatThrownBy(() -> service.deleteUser(new DeleteUserRequest("u-1", null))).hasCause(failure);
        Mockito.verify(users, Mockito.never()).findByEmail(Mockito.anyString());
        Mockito.verify(users, Mockito.never()).delete(Mockito.any());
    }

    @Test
    void failed_email_lookup_is_not_reported_as_a_missing_user() {
        Mockito.when(users.get(EMAIL)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Mockito.when(users.findByEmail(EMAIL)).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.getUser(new GetUserRequest(EMAIL, null))).hasCause(failure);
    }

    @Test
    void failed_deletion_is_not_reported_as_success() {
        Mockito.when(users.get("u-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(USER)));
        Mockito.when(users.delete(USER)).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.deleteUser(new DeleteUserRequest("u-1", null))).hasCause(failure);
        Mockito.verifyNoInteractions(accounts, credentials, sessions);
    }

    @Test
    void failed_login_lookup_does_not_create_a_session_or_credential() {
        Mockito.when(users.findByEmail(EMAIL)).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD))).hasCause(failure);
        Assertions.assertThatThrownBy(() -> service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD)))
                .hasCause(failure);
        Mockito.verifyNoInteractions(accounts, sessions, credentials, random);
    }

    @Test
    void failed_account_lookup_prevents_all_authentication_side_effects() {
        Mockito.when(users.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(USER));
        Mockito.when(credentials.get("cre-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(CREDENTIAL)));
        Mockito.when(accounts.get("a-1")).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD))).hasCause(failure);
        Assertions.assertThatThrownBy(() -> service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD)))
                .hasCause(failure);
        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(new ExchangeServiceCredentialRequest("cre-1", PASSWORD)))
                .hasCause(failure);
        Mockito.verifyNoInteractions(sessions, random);
        Mockito.verify(credentials, Mockito.never()).create(Mockito.any());
        Mockito.verify(credentials, Mockito.never()).update(Mockito.any(), Mockito.any());
    }

    @Test
    void failed_session_persistence_does_not_return_a_login_token() {
        authenticateUser();
        Mockito.when(sessions.create(Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD))).hasCause(failure);
        Mockito.verifyNoInteractions(credentials);
    }

    @Test
    void failed_credential_persistence_does_not_return_a_secret() {
        authenticateUser();
        Mockito.when(credentials.create(Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD)))
                .hasCause(failure);
        Mockito.verifyNoInteractions(sessions);
    }

    @Test
    void failed_credential_lookup_never_updates_or_issues_a_session() {
        Mockito.when(credentials.get("cre-1")).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(new ExchangeServiceCredentialRequest("cre-1", PASSWORD)))
                .hasCause(failure);
        Assertions.assertThatThrownBy(() -> service.invalidateCredential(new InvalidateCredentialRequest("cre-1"))).hasCause(failure);
        Mockito.verify(credentials, Mockito.never()).update(Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(accounts, sessions, random);
    }

    @Test
    void failed_last_use_update_prevents_session_creation() {
        authenticateCredential();
        Mockito.when(credentials.update(Mockito.eq(CREDENTIAL), Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(new ExchangeServiceCredentialRequest("cre-1", PASSWORD)))
                .hasCause(failure);
        Mockito.verifyNoInteractions(sessions, random);
    }

    @Test
    void failed_exchange_session_keeps_the_completed_last_use_update() {
        authenticateCredential();
        Credential updated = CREDENTIAL.toBuilder().lastUsedAt(NOW).build();
        Mockito.when(credentials.update(CREDENTIAL, updated)).thenReturn(CompletableFuture.completedFuture(updated));
        Mockito.when(sessions.create(Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(new ExchangeServiceCredentialRequest("cre-1", PASSWORD)))
                .hasCause(failure);
        var order = Mockito.inOrder(credentials, sessions);
        order.verify(credentials).get("cre-1");
        order.verify(credentials).update(CREDENTIAL, updated);
        order.verify(sessions).create(Mockito.any());
        Mockito.verify(credentials, Mockito.times(1)).update(Mockito.any(), Mockito.any());
    }

    @Test
    void failed_revocation_is_not_reported_as_success() {
        Mockito.when(credentials.get("cre-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(CREDENTIAL)));
        Mockito.when(credentials.update(Mockito.eq(CREDENTIAL), Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> service.invalidateCredential(new InvalidateCredentialRequest("cre-1"))).hasCause(failure);
        Mockito.verifyNoInteractions(sessions, random);
    }
}
