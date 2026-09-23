package jc121f1.service.auth;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.common.PasswordUtil;
import jc121f1.common.store.GenericStore;
import jc121f1.model.auth.api.request.CreateUserRequest;
import jc121f1.model.auth.api.request.DeleteUserRequest;
import jc121f1.model.auth.api.request.ExchangeServiceCredentialRequest;
import jc121f1.model.auth.api.request.GenerateCredentialRequest;
import jc121f1.model.auth.api.request.GetUserRequest;
import jc121f1.model.auth.api.request.InvalidateCredentialRequest;
import jc121f1.model.auth.api.request.LoginRequest;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.PublicFacingCredential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.AuthService;
import jc121f1.services.auth.AuthServiceImpl;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.SessionStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.instance.exceptions.ResourceNotFoundException;
import jc121f1.services.instance.exceptions.UnauthorizedException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Service-only tests with in-memory state; no database, server or dependency injection container. */
@MiniCloudTest
class AuthServiceLifecycleTest {
    private static final String EMAIL = "owner@example.com";
    private static final String PASSWORD = "owner password";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final Accounts accounts = new Accounts();
    private final Users users = new Users();
    private final Credentials credentials = new Credentials();
    private final Sessions sessions = new Sessions();
    private final AuthorizationService authorizationService = Mockito.mock(AuthorizationService.class);
    private final AuthService service = new AuthServiceImpl(accounts, users, Clock.fixed(NOW, ZoneOffset.UTC),
            sessions, credentials, new SecureRandom(), authorizationService);

    private static AuthenticatedSession caller(User user) {
        return new AuthenticatedSession(user.accountId(), user.userId(), Session.SubjectType.USER);
    }

    private User createOwner() {
        return service.createUser(new CreateUserRequest(EMAIL, PASSWORD, null));
    }

    @Test
    void creates_an_owner_and_logs_in_with_account_bound_wildcard_claims() {
        User owner = createOwner();
        Session session = service.login(new LoginRequest(EMAIL, PASSWORD));

        Assertions.assertThat(accounts.items.get(owner.accountId()).ownerId()).isEqualTo(owner.userId());
        Assertions.assertThat(session.subjectId()).isEqualTo(owner.userId());
        Assertions.assertThat(session.accountId()).isEqualTo(owner.accountId());
        Assertions.assertThat(session.subjectType()).isEqualTo(Session.SubjectType.USER);
        Assertions.assertThat(session.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        Assertions.assertThat(sessions.items.get(session.token())).isEqualTo(session);
        Assertions.assertThat(service.getUser(caller(owner), new GetUserRequest(EMAIL, null))).isEqualTo(owner);
        Assertions.assertThat(service.getUser(caller(owner), new GetUserRequest(null, owner.userId()))).isEqualTo(owner);
        Assertions.assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "wrong password")))
                .isInstanceOf(UnauthorizedException.class);
        Assertions.assertThat(sessions.items).hasSize(1);
    }

    @Test
    void generates_exchanges_and_revokes_a_credential_without_reusing_secrets_or_tokens() {
        User owner = createOwner();
        PublicFacingCredential first = service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD));
        PublicFacingCredential second = service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD));

        Assertions.assertThat(first.credentialId()).isNotEqualTo(second.credentialId());
        Assertions.assertThat(first.secret()).isNotEqualTo(second.secret()).doesNotContain("=");
        Credential stored = credentials.items.get(first.credentialId());
        Assertions.assertThat(stored.accountId()).isEqualTo(owner.accountId());
        Assertions.assertThat(stored.secretHash()).isNotEqualTo(first.secret());
        Assertions.assertThat(PasswordUtil.verify(first.secret().toCharArray(), stored.secretHash())).isTrue();
        Assertions.assertThat(stored.lastUsedAt()).isNull();

        Session firstSession = service.exchangeServiceCredential(new ExchangeServiceCredentialRequest(first.credentialId(), first.secret()));
        Session secondSession = service.exchangeServiceCredential(new ExchangeServiceCredentialRequest(first.credentialId(), first.secret()));
        Assertions.assertThat(firstSession.token()).isNotEqualTo(secondSession.token()).doesNotContain("=");
        Assertions.assertThat(firstSession.subjectType()).isEqualTo(Session.SubjectType.CREDENTIAL);
        Assertions.assertThat(firstSession.subjectId()).isEqualTo(first.credentialId());
        Assertions.assertThat(firstSession.accountId()).isEqualTo(owner.accountId());
        Assertions.assertThat(credentials.items.get(first.credentialId()).lastUsedAt()).isEqualTo(NOW);

        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(
                new ExchangeServiceCredentialRequest(first.credentialId(), second.secret())))
                .isInstanceOf(UnauthorizedException.class);
        service.invalidateCredential(caller(owner), new InvalidateCredentialRequest(first.credentialId()));
        service.invalidateCredential(caller(owner), new InvalidateCredentialRequest(first.credentialId()));
        Assertions.assertThat(credentials.items.get(first.credentialId()).revoked()).isTrue();
        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(
                new ExchangeServiceCredentialRequest(first.credentialId(), first.secret())))
                .isInstanceOf(UnauthorizedException.class);
        Assertions.assertThat(sessions.items).hasSize(2);
        Assertions.assertThat(service.exchangeServiceCredential(
                new ExchangeServiceCredentialRequest(second.credentialId(), second.secret())).subjectId()).isEqualTo(second.credentialId());
    }

    @Test
    void deleting_a_member_prevents_future_password_authentication_without_deleting_the_account() {
        User owner = createOwner();
        String memberEmail = "member@example.com";
        User member = service.createUser(caller(owner), new CreateUserRequest(memberEmail, PASSWORD, owner.accountId()));
        Session login = service.login(new LoginRequest(memberEmail, PASSWORD));
        Assertions.assertThat(login.accountId()).isEqualTo(owner.accountId());
        Assertions.assertThat(accounts.items).hasSize(1);

        Assertions.assertThat(service.deleteUser(caller(owner), new DeleteUserRequest(member.userId(), EMAIL))).isEqualTo(member);
        Assertions.assertThatThrownBy(() -> service.getUser(caller(owner), new GetUserRequest(null, member.userId())))
                .isInstanceOf(ResourceNotFoundException.class);
        Assertions.assertThatThrownBy(() -> service.login(new LoginRequest(memberEmail, PASSWORD))).isInstanceOf(UnauthorizedException.class);
        Assertions.assertThatThrownBy(() -> service.generateCredential(
                new GenerateCredentialRequest(memberEmail, PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        Assertions.assertThat(accounts.items).containsKey(owner.accountId());
        Assertions.assertThat(service.login(new LoginRequest(EMAIL, PASSWORD)).subjectId()).isEqualTo(owner.userId());
    }

    @Test
    void identical_wildcard_claims_keep_separate_account_identities() {
        User first = createOwner();
        User second = service.createUser(new CreateUserRequest("other@example.com", "other password", null));
        PublicFacingCredential credential = service.generateCredential(
                new GenerateCredentialRequest("other@example.com", "other password"));
        Session session = service.exchangeServiceCredential(new ExchangeServiceCredentialRequest(credential.credentialId(), credential.secret()));
        Assertions.assertThat(session.accountId()).isEqualTo(second.accountId()).isNotEqualTo(first.accountId());
        Assertions.assertThat(credential.accountId()).isEqualTo(second.accountId());
    }

    @ParameterizedTest
    @EnumSource(value = Account.AccountStatus.class, names = {"SUSPENDED", "CLOSED"})
    void account_status_changes_prevent_new_authentication_with_existing_credentials(Account.AccountStatus status) {
        User owner = createOwner();
        PublicFacingCredential credential = service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD));
        Account original = accounts.items.get(owner.accountId());
        accounts.update(original, original.toBuilder().status(status).build()).join();

        Assertions.assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD))).isInstanceOf(UnauthorizedException.class);
        Assertions.assertThatThrownBy(() -> service.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD)))
                .isInstanceOf(UnauthorizedException.class);
        Assertions.assertThatThrownBy(() -> service.exchangeServiceCredential(
                new ExchangeServiceCredentialRequest(credential.credentialId(), credential.secret())))
                .isInstanceOf(UnauthorizedException.class);
        Assertions.assertThat(sessions.items).isEmpty();
        Assertions.assertThat(credentials.items).hasSize(1);
        Assertions.assertThat(credentials.items.get(credential.credentialId()).lastUsedAt()).isNull();
    }

    private abstract static class MemoryStore<T> implements GenericStore<T> {
        final Map<String, T> items = new HashMap<>();
        private final Function<T, String> key;

        MemoryStore(Function<T, String> key) {
            this.key = key;
        }

        @Override
        public CompletableFuture<Optional<T>> get(String id) {
            return CompletableFuture.completedFuture(Optional.ofNullable(items.get(id)));
        }

        @Override
        public CompletableFuture<Optional<T>> get(String id, boolean consistentRead) {
            return get(id);
        }

        @Override
        public CompletableFuture<List<T>> list() {
            return CompletableFuture.completedFuture(List.copyOf(items.values()));
        }

        @Override
        public CompletableFuture<T> create(T item) {
            if (items.putIfAbsent(key.apply(item), item) != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Duplicate key"));
            }
            return CompletableFuture.completedFuture(item);
        }

        @Override
        public CompletableFuture<T> update(T previous, T updated) {
            if (!items.replace(key.apply(previous), previous, updated)) {
                return CompletableFuture.failedFuture(new IllegalStateException("Stale update"));
            }
            return CompletableFuture.completedFuture(updated);
        }

        @Override
        public CompletableFuture<Void> delete(T item) {
            items.remove(key.apply(item));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static class Accounts extends MemoryStore<Account> implements AccountStore {
        Accounts() {
            super(Account::accountId);
        }
    }

    private static class Credentials extends MemoryStore<Credential> implements CredentialStore {
        Credentials() {
            super(Credential::credentialId);
        }
    }

    private static class Sessions extends MemoryStore<Session> implements SessionStore {
        Sessions() {
            super(Session::token);
        }
    }

    private static class Users extends MemoryStore<User> implements UserStore {
        Users() {
            super(User::userId);
        }

        @Override
        public CompletableFuture<User> findByEmail(String email) {
            return CompletableFuture.completedFuture(items.values().stream()
                    .filter(user -> user.email().equals(email)).findFirst().orElse(null));
        }
    }
}
