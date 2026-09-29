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
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.PublicFacingCredential;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.AuthService;
import jc121f1.services.auth.AuthServiceImpl;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.SessionStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.instance.exceptions.UnauthorizedException;
import jc121f1.services.instance.exceptions.ValidationException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

@MiniCloudTest
class AuthServiceBoundaryTest {
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "valid password";
    private static final String HASH = PasswordUtil.hash(PASSWORD.toCharArray());
    private static final String ACCOUNT_ID = "a-1";
    private static final AuthenticatedSession CALLER =
            new AuthenticatedSession(ACCOUNT_ID, "u-1", Session.SubjectType.USER);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock private UserStore users;
    @Mock private AccountStore accounts;
    @Mock private CredentialStore credentials;
    @Mock private SessionStore sessions;
    @Mock private SecureRandom random;
    @Mock private AuthorizationService authorizationService;
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(accounts, users, Clock.fixed(NOW, java.time.ZoneOffset.UTC), sessions, credentials,
                random, authorizationService);
    }

    private User user() {
        return User.builder().userId("u-1").email(EMAIL).accountId(ACCOUNT_ID).passwordHash(HASH).build();
    }

    private void existingUser() {
        Mockito.when(users.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(user()));
    }

    private void account(Account.AccountStatus status) {
        Mockito.when(accounts.get(ACCOUNT_ID)).thenReturn(CompletableFuture.completedFuture(Optional.of(
                Account.builder().accountId(ACCOUNT_ID).status(status).build())));
    }

    private Credential existingCredential() {
        Credential credential = Credential.builder().credentialId("cre-1").accountId(ACCOUNT_ID)
                .secretHash(HASH).build();
        Mockito.when(credentials.get("cre-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(credential)));
        return credential;
    }

    static Stream<Consumer<AuthService>> invalidRequests() {
        Stream.Builder<Consumer<AuthService>> cases = Stream.builder();
        cases.add(service -> service.createUser(null));
        cases.add(service -> service.getUser(CALLER, null));
        cases.add(service -> service.deleteUser(CALLER, null));
        cases.add(service -> service.login(null));
        cases.add(service -> service.generateCredential(null));
        cases.add(service -> service.exchangeServiceCredential(null));
        cases.add(service -> service.invalidateCredential(CALLER, null));
        for (String invalid : new String[] {null, "", " \t"}) {
            cases.add(service -> service.createUser(new CreateUserRequest(invalid, PASSWORD, null)));
            cases.add(service -> service.createUser(new CreateUserRequest(EMAIL, invalid, null)));
            cases.add(service -> service.login(new LoginRequest(invalid, PASSWORD)));
            cases.add(service -> service.login(new LoginRequest(EMAIL, invalid)));
            cases.add(service -> service.generateCredential(new GenerateCredentialRequest(invalid, PASSWORD)));
            cases.add(service -> service.generateCredential(new GenerateCredentialRequest(EMAIL, invalid)));
            cases.add(service -> service.exchangeServiceCredential(new ExchangeServiceCredentialRequest(invalid, PASSWORD)));
            cases.add(service -> service.exchangeServiceCredential(new ExchangeServiceCredentialRequest("cre-1", invalid)));
            cases.add(service -> service.invalidateCredential(CALLER, new InvalidateCredentialRequest(invalid)));
            cases.add(service -> service.getUser(CALLER, new GetUserRequest(invalid, null)));
            cases.add(service -> service.deleteUser(CALLER, new DeleteUserRequest(null, invalid)));
        }
        for (String blank : new String[] {"", " "}) {
            cases.add(service -> service.createUser(CALLER, new CreateUserRequest(EMAIL, PASSWORD, blank)));
            cases.add(service -> service.getUser(CALLER, new GetUserRequest(EMAIL, blank)));
            cases.add(service -> service.deleteUser(CALLER, new DeleteUserRequest(blank, EMAIL)));
        }
        return cases.build();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejects_invalid_input_before_accessing_stores(Consumer<AuthService> request) {
        Assertions.assertThatThrownBy(() -> request.accept(service)).isInstanceOf(ValidationException.class);
        Mockito.verifyNoInteractions(users, accounts, credentials, sessions, random);
    }

    @Test
    void preserves_the_authenticated_account() {
        existingUser();
        account(Account.AccountStatus.ACTIVE);
        Mockito.when(credentials.create(Mockito.any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
        PublicFacingCredential result = service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD));
        Assertions.assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void identities_without_an_account_cannot_authenticate(String accountId) {
        Mockito.when(users.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(user().toBuilder().accountId(accountId).build()));
        Credential credential = Credential.builder().credentialId("cre-1").accountId(accountId).secretHash(HASH).build();
        Mockito.when(credentials.get("cre-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(credential)));

        Assertions.assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD))).isInstanceOf(UnauthorizedException.class);
        Assertions.assertThatThrownBy(() -> service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(new ExchangeServiceCredentialRequest("cre-1", PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        Mockito.verifyNoInteractions(accounts, sessions, random);
        Mockito.verify(credentials, Mockito.never()).create(Mockito.any());
        Mockito.verify(credentials, Mockito.never()).update(Mockito.any(), Mockito.any());
    }

    @ParameterizedTest
    @EnumSource(value = Account.AccountStatus.class, names = {"SUSPENDED", "CLOSED"})
    void inactive_accounts_cannot_login_or_issue_credentials(Account.AccountStatus status) {
        existingUser();
        account(status);
        Assertions.assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD))).isInstanceOf(UnauthorizedException.class);
        Assertions.assertThatThrownBy(() -> service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        Mockito.verifyNoInteractions(sessions, credentials, random);
    }

    @ParameterizedTest
    @EnumSource(value = Account.AccountStatus.class, names = {"SUSPENDED", "CLOSED"})
    void inactive_accounts_cannot_exchange_credentials(Account.AccountStatus status) {
        existingCredential();
        account(status);
        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(new ExchangeServiceCredentialRequest("cre-1", PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        Mockito.verify(credentials, Mockito.never()).update(Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(sessions, random);
    }

    @Test
    void missing_accounts_cannot_authenticate() {
        existingUser();
        existingCredential();
        Mockito.when(accounts.get(ACCOUNT_ID)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Assertions.assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD))).isInstanceOf(UnauthorizedException.class);
        Assertions.assertThatThrownBy(() -> service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(new ExchangeServiceCredentialRequest("cre-1", PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        Mockito.verify(credentials, Mockito.never()).create(Mockito.any());
        Mockito.verify(credentials, Mockito.never()).update(Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(sessions, random);
    }

    @Test
    void creates_a_user_in_an_existing_account() {
        account(Account.AccountStatus.ACTIVE);
        Mockito.when(users.create(Mockito.any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
        Assertions.assertThat(service.createUser(CALLER, new CreateUserRequest(EMAIL, PASSWORD, ACCOUNT_ID)).accountId()).isEqualTo(ACCOUNT_ID);
        Mockito.verify(accounts, Mockito.never()).create(Mockito.any());
    }
}
