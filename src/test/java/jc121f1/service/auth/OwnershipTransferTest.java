package jc121f1.service.auth;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.dagger.AuthorizationCatalogModule;
import jc121f1.model.auth.api.request.TransferOwnershipRequest;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.authz.ResourceReference;
import jc121f1.model.authz.ServiceId;
import jc121f1.services.auth.AuthServiceImpl;
import jc121f1.services.auth.authorization.AuthAction;
import jc121f1.services.auth.authorization.AuthResourceType;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.SessionStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.authz.AuthorizationRules;
import jc121f1.services.authz.AuthorizationServiceImpl;
import jc121f1.services.authz.PolicyValidator;
import jc121f1.services.authz.audit.AuditedAuthorizationService;
import jc121f1.services.authz.audit.AuthorizationAudit;
import jc121f1.services.authz.audit.AuthorizationAuditEvent;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import jc121f1.services.authz.store.PolicyStore;
import jc121f1.services.instance.exceptions.ResourceNotFoundException;
import jc121f1.services.instance.exceptions.ValidationException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@MiniCloudTest
class OwnershipTransferTest {
    private static final AuthenticatedSession OWNER = new AuthenticatedSession("a-1", "u-old", Session.SubjectType.USER);
    private static final AuthenticatedSession NEW_OWNER = new AuthenticatedSession("a-1", "u-new", Session.SubjectType.USER);
    private static final User OLD = User.builder().userId("u-old").accountId("a-1").build();
    private static final User NEW = User.builder().userId("u-new").accountId("a-1").build();
    private static final ResourceReference RESOURCE = ResourceReference.of(ServiceId.AUTH, "a-1", AuthResourceType.ACCOUNT, "a-1");
    private final AccountStore accounts = Mockito.mock(AccountStore.class);
    private final UserStore users = Mockito.mock(UserStore.class);
    private final CredentialStore credentials = Mockito.mock(CredentialStore.class);
    private final PolicyStore policies = Mockito.mock(PolicyStore.class);
    private final List<AuthorizationAuditEvent> events = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final AtomicReference<Account> state = new AtomicReference<>(account("u-old"));
    private AuthServiceImpl service;
    private AuditedAuthorizationService authorization;

    @BeforeEach
    void setup() {
        Mockito.when(accounts.get("a-1", true)).thenAnswer(call -> CompletableFuture.completedFuture(Optional.of(state.get())));
        Mockito.when(users.get("u-old", true)).thenReturn(CompletableFuture.completedFuture(Optional.of(OLD)));
        Mockito.when(users.get("u-new", true)).thenReturn(CompletableFuture.completedFuture(Optional.of(NEW)));
        Mockito.when(policies.listAttached(Mockito.any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        Mockito.when(accounts.transferOwnership(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(call -> {
            Account observed = call.getArgument(0);
            User proposed = call.getArgument(1);
            if (!state.get().equals(observed)) {
                return CompletableFuture.failedFuture(new PolicyConflictException("stale owner"));
            }
            Account updated = observed.toBuilder().ownerId(proposed.userId()).build();
            state.set(updated);
            return CompletableFuture.completedFuture(updated);
        });
        var registry = AuthorizationCatalogModule.actionRegistry();
        var audit = new AuthorizationAudit(clock, events::add);
        authorization = new AuditedAuthorizationService(new AuthorizationServiceImpl(accounts, users, credentials,
                policies, registry, new PolicyValidator(registry), new AuthorizationRules()), audit);
        service = new AuthServiceImpl(accounts, users, clock, Mockito.mock(SessionStore.class), credentials,
                new SecureRandom(), authorization, audit);
    }

    @Test
    void transfers_owner_and_moves_recovery_rights() {
        Assertions.assertThat(service.transferOwnership(OWNER, new TransferOwnershipRequest("u-new")).ownerId()).isEqualTo("u-new");
        ResourceReference userResource = ResourceReference.of(ServiceId.AUTH, "a-1", AuthResourceType.USER, "u-old");
        Assertions.assertThat(authorization.evaluate(OWNER, AuthAction.DESCRIBE_USER, userResource).outcome())
                .isEqualTo(AuthorizationDecision.Outcome.DENY);
        Assertions.assertThat(authorization.evaluate(NEW_OWNER, AuthAction.DESCRIBE_USER, userResource).outcome())
                .isEqualTo(AuthorizationDecision.Outcome.ALLOW);
        Policy deny = new Policy("p-deny", "a-1", 1, new PolicyDocument(1,
                List.of(new PolicyDocument.Statement(PolicyDocument.Effect.DENY,
                        List.of("auth:TransferOwnership"), List.of("mc:auth:a-1:account/a-1")))));
        Mockito.when(policies.listAttached(new PrincipalReference("a-1", "u-new", Session.SubjectType.USER)))
                .thenReturn(CompletableFuture.completedFuture(List.of(deny)));
        Assertions.assertThat(authorization.evaluate(OWNER, AuthAction.TRANSFER_OWNERSHIP, RESOURCE).outcome())
                .isEqualTo(AuthorizationDecision.Outcome.DENY);
        Assertions.assertThat(authorization.evaluate(NEW_OWNER, AuthAction.TRANSFER_OWNERSHIP, RESOURCE).outcome())
                .isEqualTo(AuthorizationDecision.Outcome.ALLOW);
        Assertions.assertThatThrownBy(() -> service.transferOwnership(OWNER, new TransferOwnershipRequest("u-old")))
                .isInstanceOf(AuthorizationDeniedException.class);
        Assertions.assertThat(events).anySatisfy(event -> {
            Assertions.assertThat(event.kind()).isEqualTo(AuthorizationAuditEvent.Kind.IDENTITY_OPERATION);
            Assertions.assertThat(event.outcome()).isEqualTo(AuthorizationAuditEvent.Outcome.SUCCESS);
        });
    }

    @Test
    void rejects_credential_and_member_without_writing() {
        var credential = new AuthenticatedSession("a-1", "cre-1", Session.SubjectType.CREDENTIAL);
        Mockito.when(credentials.get("cre-1", true)).thenReturn(CompletableFuture.completedFuture(Optional.of(
                Credential.builder().credentialId("cre-1").accountId("a-1")
                        .createdByUserId(OLD.userId()).revoked(false).build())));
        Assertions.assertThatThrownBy(() -> service.transferOwnership(NEW_OWNER, new TransferOwnershipRequest("u-old")))
                .isInstanceOf(AuthorizationDeniedException.class);
        Assertions.assertThatThrownBy(() -> service.transferOwnership(credential, new TransferOwnershipRequest("u-new")))
                .isInstanceOf(AuthorizationDeniedException.class);
        Assertions.assertThat(events).anyMatch(event -> event.kind() == AuthorizationAuditEvent.Kind.DECISION
                && "CREDENTIAL_OPERATION_FORBIDDEN".equals(event.reason()));
        Mockito.verify(accounts, Mockito.never()).transferOwnership(Mockito.any(), Mockito.any(), Mockito.any());
        Assertions.assertThat(state.get().ownerId()).isEqualTo("u-old");
    }

    @Test
    void rejects_invalid_or_foreign_target_and_records_failure() {
        Assertions.assertThatThrownBy(() -> service.transferOwnership(OWNER, new TransferOwnershipRequest(" ")))
                .isInstanceOf(ValidationException.class);
        Mockito.when(users.get("u-foreign", true)).thenReturn(CompletableFuture.completedFuture(Optional.of(
                User.builder().userId("u-foreign").accountId("a-2").build())));
        Assertions.assertThatThrownBy(() -> service.transferOwnership(OWNER, new TransferOwnershipRequest("u-foreign")))
                .isInstanceOf(ResourceNotFoundException.class);
        Mockito.verify(accounts, Mockito.never()).transferOwnership(Mockito.any(), Mockito.any(), Mockito.any());
        Assertions.assertThat(events.stream().filter(e -> e.kind() == AuthorizationAuditEvent.Kind.IDENTITY_OPERATION))
                .allSatisfy(e -> Assertions.assertThat(e.outcome()).isEqualTo(AuthorizationAuditEvent.Outcome.ERROR));
    }

    @Test
    void stale_transfer_conflicts_and_same_owner_is_idempotent() {
        Assertions.assertThat(service.transferOwnership(OWNER, new TransferOwnershipRequest("u-old")).ownerId()).isEqualTo("u-old");
        Mockito.doReturn(CompletableFuture.failedFuture(new PolicyConflictException("stale owner")))
                .when(accounts).transferOwnership(Mockito.any(), Mockito.eq(NEW), Mockito.any());
        Assertions.assertThatThrownBy(() -> service.transferOwnership(OWNER, new TransferOwnershipRequest("u-new")))
                .isInstanceOf(PolicyConflictException.class);
        Assertions.assertThat(state.get().ownerId()).isEqualTo("u-old");
    }

    @Test
    void storage_failure_does_not_report_a_completed_transfer() {
        Mockito.doReturn(CompletableFuture.failedFuture(new AuthorizationStoreException("unavailable", new IllegalStateException())))
                .when(accounts).transferOwnership(Mockito.any(), Mockito.eq(NEW), Mockito.any());
        Assertions.assertThatThrownBy(() -> service.transferOwnership(OWNER, new TransferOwnershipRequest("u-new")))
                .isInstanceOf(AuthorizationStoreException.class);
        Assertions.assertThat(state.get().ownerId()).isEqualTo("u-old");
        Assertions.assertThat(events).anySatisfy(event -> {
            Assertions.assertThat(event.kind()).isEqualTo(AuthorizationAuditEvent.Kind.IDENTITY_OPERATION);
            Assertions.assertThat(event.reason()).isEqualTo("STORAGE_FAILURE");
        });
        Assertions.assertThat(events).noneMatch(event -> event.kind() == AuthorizationAuditEvent.Kind.IDENTITY_OPERATION
                && event.outcome() == AuthorizationAuditEvent.Outcome.SUCCESS);
    }

    private static Account account(String owner) {
        return Account.builder().accountId("a-1").ownerId(owner).status(Account.AccountStatus.ACTIVE).build();
    }
}
