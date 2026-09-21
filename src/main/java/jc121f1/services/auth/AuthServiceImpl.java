package jc121f1.services.auth;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.auth.AuthenticateRequest;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.PublicFacingCredential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.services.instance.exceptions.UnauthorizedException;
import jc121f1.common.PasswordUtil;
import jc121f1.model.auth.api.request.CreateUserRequest;
import jc121f1.model.auth.api.request.DeleteUserRequest;
import jc121f1.model.auth.api.request.ExchangeServiceCredentialRequest;
import jc121f1.model.auth.api.request.GenerateCredentialRequest;
import jc121f1.model.auth.api.request.GetUserRequest;
import jc121f1.model.auth.api.request.InvalidateCredentialRequest;
import jc121f1.model.auth.api.request.LoginRequest;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.SessionStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.instance.exceptions.ResourceNotFoundException;
import jc121f1.services.instance.exceptions.ValidationException;

import javax.inject.Inject;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAmount;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

public class AuthServiceImpl implements AuthService {

    private static final TemporalAmount SESSION_TTL = Duration.of(60, ChronoUnit.MINUTES);
    private static final String DUMMY_HASH = PasswordUtil.hash(UUID.randomUUID().toString().toCharArray());
    private static final int SECRET_BYTES = 32;
    private final AccountStore accountStore;
    private final UserStore userStore;
    private final Clock clock;
    private final SessionStore sessionStore;
    private final CredentialStore credentialStore;
    private final SecureRandom secureRandom;

    @Inject
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "SecureRandom is an intentionally shared, thread-safe injected dependency; "
                    + "retaining it also allows controlled randomness in tests."
    )
    public AuthServiceImpl(AccountStore accountStore,
                    UserStore userStore,
                    Clock clock,
                    SessionStore sessionStore, CredentialStore credentialStore,
                    SecureRandom secureRandom) {
        this.accountStore = accountStore;
        this.userStore = userStore;
        this.clock = clock;
        this.sessionStore = sessionStore;
        this.credentialStore = credentialStore;
        this.secureRandom = secureRandom;
    }

    @Override
    public User createUser(CreateUserRequest createUserRequest) {
        requireRequest(createUserRequest);
        requireText(createUserRequest.userEmail(), "email");
        requireText(createUserRequest.password(), "password");
        if (createUserRequest.accountId() != null) {
            requireText(createUserRequest.accountId(), "accountId");
        }
        String userId = "u-" + UUID.randomUUID();
        String email = createUserRequest.userEmail();
        String passwordHash = PasswordUtil.hash(createUserRequest.password().toCharArray());

        Account account;
        boolean accountCreated = false;
        String accountId;
        if (createUserRequest.accountId() == null) {
            account = createAccount(userId, email);
            accountId = account.accountId();
            accountCreated = true;
        } else {
            Optional<Account> maybeAccount = accountStore.get(createUserRequest.accountId()).join();
            if (maybeAccount.isPresent()) {
                account = maybeAccount.get();
                accountId = account.accountId();
                if (maybeAccount.get().status() != Account.AccountStatus.ACTIVE) {
                    throw new ValidationException("Cannot add user to a non-active account");
                }
            } else {
                throw new ResourceNotFoundException("Account not found");
            }
        }

        try {
            return userStore.create(User.builder()
                    .userId(userId)
                    .email(email)
                    .passwordHash(passwordHash)
                    .accountId(accountId)
                    .createdAt(clock.instant())
                    .build()
            ).join();
        } catch (RuntimeException e) {
            if (accountCreated) {
                rollbackAccount(account, e);
            }
            throw e;
        }
    }

    private void rollbackAccount(Account account, Exception cause) {
        try {
            accountStore.delete(account).join();
        } catch (RuntimeException rollbackFailure) {
            // Both the original failure and the rollback failure matter here —
            // suppressing one would hide a real problem (an orphaned account).
            cause.addSuppressed(rollbackFailure);
        }
    }

    @Override
    public User getUser(GetUserRequest getUserRequest) {
        requireRequest(getUserRequest);
        String identifier = Optional.ofNullable(getUserRequest.userId()).orElse(getUserRequest.email());
        return getUser(identifier);
    }

    private User getUser(String identifier) {
        requireText(identifier, "userId or email");
        return userStore.get(identifier).join()
                .or(() -> Optional.ofNullable(userStore.findByEmail(identifier).join()))
                .orElseThrow(() -> new ResourceNotFoundException(identifier));
    }

    @Override
    public User deleteUser(DeleteUserRequest deleteUserRequest) {
        requireRequest(deleteUserRequest);
        String identifier = Optional.ofNullable(deleteUserRequest.userId()).orElse(deleteUserRequest.email());
        User user = getUser(identifier);
        userStore.delete(user).join();
        return user;
    }

    @Override
    public Session login(LoginRequest loginRequest) {
        requireRequest(loginRequest);
        requireText(loginRequest.email(), "email");
        requireText(loginRequest.password(), "password");
        Optional<User> maybeUser = Optional.ofNullable(userStore.findByEmail(loginRequest.email()).join());
        String hashToCheck = maybeUser.map(User::passwordHash).orElse(DUMMY_HASH);
        boolean valid = PasswordUtil.verify(loginRequest.password().toCharArray(), hashToCheck);
        if (maybeUser.isEmpty() || !valid) {
            throw new UnauthorizedException("Invalid credentials");
        }

        User user = maybeUser.get();
        requireActiveAccount(user.accountId());
        return sessionStore.create(Session.builder()
                .token(generateOpaqueToken())
                .accountId(user.accountId())
                .subjectId(user.userId())
                .subjectType(Session.SubjectType.USER)
                .expiresAt(clock.instant().plus(SESSION_TTL))
                .build()
        ).join();
    }

    @Override
    public Session exchangeServiceCredential(ExchangeServiceCredentialRequest request) {
        requireRequest(request);
        requireText(request.credentialId(), "credentialId");
        requireText(request.secret(), "secret");
        Optional<Credential> maybeCredential = credentialStore.get(request.credentialId()).join();
        String hashToCheck = maybeCredential.map(Credential::secretHash).orElse(DUMMY_HASH);
        boolean valid = PasswordUtil.verify(request.secret().toCharArray(), hashToCheck);
        if (maybeCredential.isEmpty() || !valid) {
            throw new UnauthorizedException("Invalid credentials");
        }

        Credential credential = maybeCredential.get();

        if (!credential.isUsable()) {
            throw new  UnauthorizedException("Invalid credentials");
        }
        requireActiveAccount(credential.accountId());
        credentialStore.update(credential,
                credential.toBuilder().lastUsedAt(clock.instant()).build()).join();
        return sessionStore.create(Session.builder()
                .token(generateOpaqueToken())
                .accountId(credential.accountId())
                .subjectId(credential.credentialId())
                .subjectType(Session.SubjectType.CREDENTIAL)
                .expiresAt(clock.instant().plus(SESSION_TTL))
                .build()
        ).join();
    }

    @Override
    public PublicFacingCredential generateCredential(GenerateCredentialRequest request) {
        requireRequest(request);
        requireText(request.email(), "email");
        requireText(request.password(), "password");
        String credentialId = "cre-" + UUID.randomUUID();
        Optional<User> maybeUser = Optional.ofNullable(userStore.findByEmail(request.email()).join());
        String hashToCheck = maybeUser.map(User::passwordHash).orElse(DUMMY_HASH);
        boolean valid = PasswordUtil.verify(request.password().toCharArray(), hashToCheck);
        if (maybeUser.isEmpty() || !valid) {
            throw new UnauthorizedException("Invalid credentials");
        }

        User user = maybeUser.get();
        requireActiveAccount(user.accountId());

        byte[] secretBytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secretBytes);
        String plaintextSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        String secretHash = PasswordUtil.hash(plaintextSecret.toCharArray());
        Credential credential = credentialStore.create(Credential.builder()
                .credentialId(credentialId)
                .secretHash(secretHash)
                .accountId(user.accountId())
                .revoked(false)
                .createdAt(clock.instant())
                .lastUsedAt(null)
                .build()).join();
        return PublicFacingCredential.builder()
                .credentialId(credential.credentialId())
                .accountId(credential.accountId())
                .secret(plaintextSecret)
                .build();
    }

    @Override
    public AuthenticatedSession authenticate(AuthenticateRequest request) {
        requireRequest(request);

        if (request.bearerToken() == null) {
            throw new UnauthorizedException("Invalid credentials");
        }

        String token = request.bearerToken();
        if (token.isBlank()) {
            throw new UnauthorizedException("Invalid credentials");
        }

        Session session = sessionStore.get(token).join()
                .orElseThrow(() ->
                        new UnauthorizedException("Invalid credentials"));

        if (session.expiresAt() == null
                || !session.expiresAt().isAfter(clock.instant())) {
            throw new UnauthorizedException("Invalid credentials");
        }

        requireActiveAccount(session.accountId());
        return AuthenticatedSession.builder()
                .accountId(session.accountId())
                .subjectId(session.subjectId())
                .subjectType(session.subjectType())
                .build();
    }

    @Override
    public void invalidateCredential(InvalidateCredentialRequest request) {
        requireRequest(request);
        requireText(request.credentialId(), "credentialId");
        Optional<Credential> maybeCredential = credentialStore.get(request.credentialId()).join();
        if (maybeCredential.isEmpty()) {
            throw new ResourceNotFoundException(request.credentialId());
        }

        Credential credential = maybeCredential.get();

        credentialStore.update(credential, credential.toBuilder().revoked(true).build()).join();
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireActiveAccount(String accountId) {
        if (accountId == null || accountId.isBlank()
                || accountStore.get(accountId).join()
                .filter(account -> account.status() == Account.AccountStatus.ACTIVE).isEmpty()) {
            throw new UnauthorizedException("Invalid credentials");
        }
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw new ValidationException("Request is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " is required");
        }
    }

    private Account createAccount(String userId, String email) {
        Instant now = clock.instant();
        return accountStore.create(Account.builder()
                .accountId("a-" + UUID.randomUUID())
                .name(email)
                .status(Account.AccountStatus.ACTIVE)
                .ownerId(userId)
                .createdAt(now)
                .updatedAt(now)
                .build()
        ).join();
    }
}
