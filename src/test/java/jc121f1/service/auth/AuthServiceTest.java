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
import jc121f1.services.instance.exceptions.ValidationException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Base64;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@MiniCloudTest
public class AuthServiceTest {
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String OTHER_PASSWORD = "wrong-password";
    private static final String ACCOUNT_ID = "a-existing";
    private static final AuthenticatedSession CALLER =
            new AuthenticatedSession(ACCOUNT_ID, "u-existing", Session.SubjectType.USER);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PASSWORD_HASH = PasswordUtil.hash(PASSWORD.toCharArray());

    private AuthService authService;

    @Mock private UserStore userStore;
    @Mock private CredentialStore credentialStore;
    @Mock private SessionStore sessionStore;
    @Mock private AccountStore accountStore;
    @Mock private Clock clock;
    @Mock private SecureRandom secureRandom;
    @Mock private AuthorizationService authorizationService;

    @Nested
    class Given_a_auth_service {
        @BeforeEach
        void setUp() {
            authService = new AuthServiceImpl(
                    accountStore,
                    userStore,
                    clock,
                    sessionStore,
                    credentialStore,
                    secureRandom, authorizationService
            );
        }

        private User user() {
            return User.builder().userId("u-existing").email(EMAIL).accountId(ACCOUNT_ID)
                    .passwordHash(PASSWORD_HASH).createdAt(NOW).build();
        }

        private Credential credential() {
            return Credential.builder().credentialId("cre-existing").accountId(ACCOUNT_ID)
                    .createdByUserId("u-existing")
                    .secretHash(PASSWORD_HASH).createdAt(NOW).revoked(false).build();
        }

        private void prepareActiveAccount() {
            Mockito.when(accountStore.get(ACCOUNT_ID)).thenReturn(CompletableFuture.completedFuture(
                    Optional.of(Account.builder().accountId(ACCOUNT_ID).status(Account.AccountStatus.ACTIVE).build())));
        }

        private void prepareRandom() {
            Mockito.doAnswer(invocation -> {
                byte[] bytes = invocation.getArgument(0);
                Arrays.fill(bytes, (byte) 42);
                return null;
            }).when(secureRandom).nextBytes(Mockito.any(byte[].class));
        }

        private void prepareUserCreation() {
            Mockito.when(clock.instant()).thenReturn(NOW);
            Mockito.when(userStore.create(Mockito.any()))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(invocation.getArgument(0)));
        }

        private Account prepareNewAccount() {
            Account account = Account.builder().accountId(ACCOUNT_ID).status(Account.AccountStatus.ACTIVE).build();
            Mockito.when(clock.instant()).thenReturn(NOW);
            Mockito.when(accountStore.create(Mockito.any())).thenReturn(CompletableFuture.completedFuture(account));
            return account;
        }

        @Nested
        class When_creating_users {
            @Test
            void creates_an_account_owned_by_the_new_user_and_hashes_the_password() {
                prepareUserCreation();
                Mockito.when(accountStore.create(Mockito.any()))
                        .thenAnswer(invocation -> CompletableFuture.completedFuture(invocation.getArgument(0)));

                User result = authService.createUser(new CreateUserRequest(EMAIL, PASSWORD, null));

                ArgumentCaptor<Account> account = ArgumentCaptor.forClass(Account.class);
                Mockito.verify(accountStore).create(account.capture());
                Assertions.assertThat(result.userId()).startsWith("u-");
                Assertions.assertThat(result.email()).isEqualTo(EMAIL);
                Assertions.assertThat(result.createdAt()).isEqualTo(NOW);
                Assertions.assertThat(result.accountId()).isEqualTo(account.getValue().accountId()).startsWith("a-");
                Assertions.assertThat(account.getValue().ownerId()).isEqualTo(result.userId());
                Assertions.assertThat(account.getValue().name()).isEqualTo(EMAIL);
                Assertions.assertThat(account.getValue().status()).isEqualTo(Account.AccountStatus.ACTIVE);
                Assertions.assertThat(account.getValue().createdAt()).isEqualTo(NOW);
                Assertions.assertThat(account.getValue().updatedAt()).isEqualTo(NOW);
                Assertions.assertThat(result.passwordHash()).isNotEqualTo(PASSWORD);
                Assertions.assertThat(PasswordUtil.verify(PASSWORD.toCharArray(), result.passwordHash())).isTrue();
            }

            @Test
            void adds_a_user_to_an_active_account() {
                prepareUserCreation();
                Mockito.when(accountStore.get(ACCOUNT_ID)).thenReturn(CompletableFuture.completedFuture(
                        Optional.of(Account.builder().accountId(ACCOUNT_ID).status(Account.AccountStatus.ACTIVE).build())));

                User result = authService.createUser(CALLER, new CreateUserRequest(EMAIL, PASSWORD, ACCOUNT_ID));

                Assertions.assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
                Mockito.verify(accountStore, Mockito.never()).create(Mockito.any());
            }



            @Test
            void rejects_a_missing_account() {
                Mockito.when(accountStore.get(ACCOUNT_ID)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
                Assertions.assertThatThrownBy(() -> authService.createUser(CALLER,
                        new CreateUserRequest(EMAIL, PASSWORD, ACCOUNT_ID)))
                        .isInstanceOf(ResourceNotFoundException.class);
                Mockito.verifyNoInteractions(userStore);
            }

            @Test
            void rejects_suspended_and_closed_accounts() {
                for (Account.AccountStatus status : new Account.AccountStatus[] {
                        Account.AccountStatus.SUSPENDED, Account.AccountStatus.CLOSED}) {
                    Mockito.when(accountStore.get(ACCOUNT_ID)).thenReturn(CompletableFuture.completedFuture(
                            Optional.of(Account.builder().accountId(ACCOUNT_ID).status(status).build())));
                    Assertions.assertThatThrownBy(() -> authService.createUser(CALLER,
                            new CreateUserRequest(EMAIL, PASSWORD, ACCOUNT_ID)))
                            .isInstanceOf(ValidationException.class);
                }
                Mockito.verifyNoInteractions(userStore);
            }

            @Test
            void rolls_back_a_new_account_if_user_creation_fails() {
                Account account = prepareNewAccount();
                RuntimeException failure = new IllegalStateException("user creation failed");
                Mockito.when(userStore.create(Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure));
                Mockito.when(accountStore.delete(account)).thenReturn(CompletableFuture.completedFuture(null));

                Assertions.assertThatThrownBy(() -> authService.createUser(
                        new CreateUserRequest(EMAIL, PASSWORD, null)))
                        .isInstanceOf(CompletionException.class).hasCause(failure);
                Mockito.verify(accountStore).delete(account);
            }

            @Test
            void preserves_both_failures_when_rollback_fails() {
                Account account = prepareNewAccount();
                RuntimeException failure = new IllegalStateException("user creation failed");
                RuntimeException rollbackFailure = new IllegalStateException("rollback failed");
                Mockito.when(userStore.create(Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure));
                Mockito.when(accountStore.delete(account)).thenReturn(CompletableFuture.failedFuture(rollbackFailure));

                Throwable thrown = Assertions.catchThrowable(() -> authService.createUser(
                        new CreateUserRequest(EMAIL, PASSWORD, null)));

                Assertions.assertThat(thrown).hasCause(failure);
                Assertions.assertThat(thrown.getSuppressed()).hasSize(1);
                Assertions.assertThat(thrown.getSuppressed()[0]).hasCause(rollbackFailure);
            }

            @Test
            void never_rolls_back_an_existing_account() {
                Mockito.when(clock.instant()).thenReturn(NOW);
                Mockito.when(accountStore.get(ACCOUNT_ID)).thenReturn(CompletableFuture.completedFuture(
                        Optional.of(Account.builder().accountId(ACCOUNT_ID).status(Account.AccountStatus.ACTIVE).build())));
                RuntimeException failure = new IllegalStateException("user creation failed");
                Mockito.when(userStore.create(Mockito.any())).thenReturn(CompletableFuture.failedFuture(failure));
                Assertions.assertThatThrownBy(() -> authService.createUser(CALLER,
                        new CreateUserRequest(EMAIL, PASSWORD, ACCOUNT_ID))).hasCause(failure);
                Mockito.verify(accountStore, Mockito.never()).delete(Mockito.any());
            }
        }

        @Nested
        class When_finding_and_deleting_users {
            @Test
            void looks_up_by_id_in_preference_to_email() {
                User expected = user();
                Mockito.when(userStore.get(expected.userId())).thenReturn(CompletableFuture.completedFuture(Optional.of(expected)));
                Assertions.assertThat(authService.getUser(CALLER, new GetUserRequest(EMAIL, expected.userId()))).isEqualTo(expected);
                Mockito.verify(userStore, Mockito.never()).findByEmail(Mockito.anyString());
            }

            @Test
            void finds_a_user_by_email() {
                Mockito.when(userStore.get(EMAIL)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
                Mockito.when(userStore.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(user()));
                Assertions.assertThat(authService.getUser(CALLER, new GetUserRequest(EMAIL, null))).isEqualTo(user());
            }

            @Test
            void reports_a_missing_user() {
                Mockito.when(userStore.get(EMAIL)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
                Mockito.when(userStore.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(null));
                Assertions.assertThatThrownBy(() -> authService.getUser(CALLER, new GetUserRequest(EMAIL, null)))
                        .isInstanceOf(ResourceNotFoundException.class);
            }

            @Test
            void deletes_and_returns_the_user_found_by_email() {
                User expected = user();
                Mockito.when(userStore.get(EMAIL)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
                Mockito.when(userStore.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(expected));
                Mockito.when(userStore.delete(expected)).thenReturn(CompletableFuture.completedFuture(null));
                Assertions.assertThat(authService.deleteUser(CALLER, new DeleteUserRequest(null, EMAIL))).isEqualTo(expected);
                Mockito.verify(userStore).delete(expected);
            }

            @Test
            void does_not_delete_a_missing_user() {
                Mockito.when(userStore.get("missing")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
                Mockito.when(userStore.findByEmail("missing")).thenReturn(CompletableFuture.completedFuture(null));
                Assertions.assertThatThrownBy(() -> authService.deleteUser(CALLER, new DeleteUserRequest("missing", null)))
                        .isInstanceOf(ResourceNotFoundException.class);
                Mockito.verify(userStore, Mockito.never()).delete(Mockito.any());
            }
        }

        @Nested
        class When_logging_in {
            @Test
            void creates_a_random_session_with_user_identity_and_one_hour_expiry() {
                prepareActiveAccount();
                Mockito.when(userStore.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(user()));
                Mockito.when(clock.instant()).thenReturn(NOW);
                prepareRandom();
                Mockito.when(sessionStore.create(Mockito.any()))
                        .thenAnswer(invocation -> CompletableFuture.completedFuture(invocation.getArgument(0)));

                Session result = authService.login(new LoginRequest(EMAIL, PASSWORD));

                Assertions.assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
                Assertions.assertThat(result.subjectId()).isEqualTo(user().userId());
                Assertions.assertThat(result.subjectType()).isEqualTo(Session.SubjectType.USER);
                Assertions.assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
                Assertions.assertThat(Base64.getUrlDecoder().decode(result.token())).hasSize(32).containsOnly((byte) 42);
                Mockito.verify(sessionStore).create(result);
            }

            @Test
            void rejects_a_wrong_password_without_creating_a_session() {
                Mockito.when(userStore.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(user()));
                Assertions.assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, OTHER_PASSWORD)))
                        .isInstanceOf(UnauthorizedException.class).hasMessage("Invalid credentials");
                Mockito.verifyNoInteractions(sessionStore, secureRandom);
            }

            @Test
            void rejects_an_unknown_user_as_invalid_credentials() {
                Mockito.when(userStore.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(null));
                Assertions.assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                        .isInstanceOf(UnauthorizedException.class).hasMessage("Invalid credentials");
                Mockito.verifyNoInteractions(sessionStore, secureRandom);
            }
        }

        @Nested
        class When_exchanging_credentials {
            @Test
            void records_last_use_and_creates_a_credential_session() {
                prepareActiveAccount();
                Credential existing = credential();
                Mockito.when(credentialStore.get(existing.credentialId())).thenReturn(CompletableFuture.completedFuture(Optional.of(existing)));
                Mockito.when(clock.instant()).thenReturn(NOW.plusSeconds(30));
                Mockito.when(credentialStore.update(Mockito.eq(existing), Mockito.any()))
                        .thenAnswer(invocation -> CompletableFuture.completedFuture(invocation.getArgument(1)));
                Mockito.when(sessionStore.create(Mockito.any()))
                        .thenAnswer(invocation -> CompletableFuture.completedFuture(invocation.getArgument(0)));
                prepareRandom();

                Session result = authService.exchangeServiceCredential(
                        new ExchangeServiceCredentialRequest(existing.credentialId(), PASSWORD));

                Mockito.verify(credentialStore).update(existing, existing.toBuilder().lastUsedAt(NOW.plusSeconds(30)).build());
                Assertions.assertThat(result.subjectType()).isEqualTo(Session.SubjectType.CREDENTIAL);
                Assertions.assertThat(result.subjectId()).isEqualTo(existing.credentialId());
                Assertions.assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
                Assertions.assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(3630));
                Assertions.assertThat(Base64.getUrlDecoder().decode(result.token())).hasSize(32).containsOnly((byte) 42);
                Mockito.verify(sessionStore).create(result);
            }

            @Test
            void rejects_unknown_credentials() {
                Mockito.when(credentialStore.get("missing")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
                Assertions.assertThatThrownBy(() -> authService.exchangeServiceCredential(
                        new ExchangeServiceCredentialRequest("missing", PASSWORD)))
                        .isInstanceOf(UnauthorizedException.class).hasMessage("Invalid credentials");
                Mockito.verifyNoInteractions(sessionStore, secureRandom);
                Mockito.verify(credentialStore, Mockito.never()).update(Mockito.any(), Mockito.any());
            }

            @Test
            void rejects_wrong_secrets() {
                Credential existing = credential();
                Mockito.when(credentialStore.get(existing.credentialId())).thenReturn(CompletableFuture.completedFuture(Optional.of(existing)));
                Assertions.assertThatThrownBy(() -> authService.exchangeServiceCredential(
                        new ExchangeServiceCredentialRequest(existing.credentialId(), OTHER_PASSWORD)))
                        .isInstanceOf(UnauthorizedException.class);
                Mockito.verifyNoInteractions(sessionStore, secureRandom);
                Mockito.verify(credentialStore, Mockito.never()).update(Mockito.any(), Mockito.any());
            }

            @Test
            void rejects_revoked_credentials_even_with_the_correct_secret() {
                Credential existing = credential().toBuilder().revoked(true).build();
                Mockito.when(credentialStore.get(existing.credentialId())).thenReturn(CompletableFuture.completedFuture(Optional.of(existing)));
                Assertions.assertThatThrownBy(() -> authService.exchangeServiceCredential(
                        new ExchangeServiceCredentialRequest(existing.credentialId(), PASSWORD)))
                        .isInstanceOf(UnauthorizedException.class);
                Mockito.verifyNoInteractions(sessionStore, secureRandom);
                Mockito.verify(credentialStore, Mockito.never()).update(Mockito.any(), Mockito.any());
            }
        }

        @Nested
        class When_generating_credentials {
            @Test
            void returns_the_secret_but_only_persists_its_hash() {
                prepareActiveAccount();
                Mockito.when(userStore.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(user()));
                Mockito.when(clock.instant()).thenReturn(NOW);
                prepareRandom();
                Mockito.when(credentialStore.create(Mockito.any()))
                        .thenAnswer(invocation -> CompletableFuture.completedFuture(invocation.getArgument(0)));

                PublicFacingCredential result = authService.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD));

                ArgumentCaptor<Credential> stored = ArgumentCaptor.forClass(Credential.class);
                Mockito.verify(credentialStore).create(stored.capture());
                Credential persisted = stored.getValue();
                Assertions.assertThat(persisted.createdByUserId()).isEqualTo(user().userId());
                Assertions.assertThat(result.credentialId()).startsWith("cre-").isEqualTo(persisted.credentialId());
                Assertions.assertThat(result.accountId()).isEqualTo(ACCOUNT_ID).isEqualTo(persisted.accountId());
                Assertions.assertThat(Base64.getUrlDecoder().decode(result.secret())).hasSize(32).containsOnly((byte) 42);
                Assertions.assertThat(persisted.secretHash()).isNotEqualTo(result.secret());
                Assertions.assertThat(PasswordUtil.verify(result.secret().toCharArray(), persisted.secretHash())).isTrue();
                Assertions.assertThat(persisted.revoked()).isFalse();
                Assertions.assertThat(persisted.createdAt()).isEqualTo(NOW);
                Assertions.assertThat(persisted.lastUsedAt()).isNull();
            }

            @Test
            void rejects_a_wrong_password() {
                Mockito.when(userStore.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(user()));
                Assertions.assertThatThrownBy(() -> authService.generateCredential(new GenerateCredentialRequest(EMAIL, OTHER_PASSWORD)))
                        .isInstanceOf(UnauthorizedException.class);
                Mockito.verifyNoInteractions(credentialStore, secureRandom);
            }

            @Test
            void rejects_an_unknown_user() {
                Mockito.when(userStore.findByEmail(EMAIL)).thenReturn(CompletableFuture.completedFuture(null));
                Assertions.assertThatThrownBy(() -> authService.generateCredential(new GenerateCredentialRequest(EMAIL, PASSWORD)))
                        .isInstanceOf(UnauthorizedException.class);
                Mockito.verifyNoInteractions(credentialStore, secureRandom);
            }
        }

        @Nested
        class When_invalidating_credentials {
            @Test
            void revokes_the_credential_preserving_other_fields() {
                Credential existing = credential();
                Mockito.when(credentialStore.get(existing.credentialId())).thenReturn(CompletableFuture.completedFuture(Optional.of(existing)));
                Mockito.when(credentialStore.update(Mockito.eq(existing), Mockito.any()))
                        .thenAnswer(invocation -> CompletableFuture.completedFuture(invocation.getArgument(1)));
                authService.invalidateCredential(CALLER, new InvalidateCredentialRequest(existing.credentialId()));
                Mockito.verify(credentialStore).update(existing, existing.toBuilder().revoked(true).build());
            }

            @Test
            void reports_a_missing_credential_without_updating() {
                Mockito.when(credentialStore.get("missing")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
                Assertions.assertThatThrownBy(() -> authService.invalidateCredential(CALLER, new InvalidateCredentialRequest("missing")))
                        .isInstanceOf(ResourceNotFoundException.class);
                Mockito.verify(credentialStore, Mockito.never()).update(Mockito.any(), Mockito.any());
            }
        }
    }
}
