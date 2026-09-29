package jc121f1.service.authz;

import jc121f1.dagger.AuthorizationCatalogModule;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.authz.AuthorizationRules;
import jc121f1.services.authz.AuthorizationServiceImpl;
import jc121f1.services.authz.PolicyService;
import jc121f1.services.authz.PolicyServiceImpl;
import jc121f1.services.authz.PolicyValidator;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import jc121f1.services.authz.exceptions.PolicyNotFoundException;
import jc121f1.services.authz.exceptions.PolicyValidationException;
import jc121f1.services.authz.store.PolicyStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

class PolicyServiceTest {
    private static final String ACCOUNT = "a-1";
    private static final AuthenticatedSession OWNER = new AuthenticatedSession(ACCOUNT, "u-owner", Session.SubjectType.USER);
    private static final AuthenticatedSession MEMBER = new AuthenticatedSession(ACCOUNT, "u-member", Session.SubjectType.USER);
    private static final AuthenticatedSession MACHINE = new AuthenticatedSession(ACCOUNT, "cre-1", Session.SubjectType.CREDENTIAL);
    private static final PrincipalReference TARGET = new PrincipalReference(ACCOUNT, "u-member", Session.SubjectType.USER);
    private static final PrincipalReference CREDENTIAL = new PrincipalReference(ACCOUNT, "cre-1", Session.SubjectType.CREDENTIAL);
    private AccountStore accounts;
    private UserStore users;
    private CredentialStore credentials;
    private PolicyStore policies;
    private PolicyService service;

    @BeforeEach
    void setup() {
        accounts = Mockito.mock(AccountStore.class);
        users = Mockito.mock(UserStore.class);
        credentials = Mockito.mock(CredentialStore.class);
        policies = Mockito.mock(PolicyStore.class);
        var registry = AuthorizationCatalogModule.actionRegistry();
        var validator = new PolicyValidator(registry);
        var evaluator = new AuthorizationServiceImpl(accounts, users, credentials, policies, registry,
                validator, new AuthorizationRules());
        service = new PolicyServiceImpl(evaluator, policies, validator, users, credentials);
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account()));
        Mockito.when(users.get(OWNER.subjectId(), true)).thenReturn(found(user(OWNER.subjectId())));
        Mockito.when(users.get(MEMBER.subjectId(), true)).thenReturn(found(user(MEMBER.subjectId())));
        Mockito.when(credentials.get(MACHINE.subjectId(), true)).thenReturn(found(credential()));
    }

    @Test
    void every_operation_rejects_members_and_credentials_before_policy_access() {
        for (AuthenticatedSession caller : List.of(MEMBER, MACHINE)) {
            assertDenied(caller);
        }
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void every_operation_rechecks_active_account_current_owner_and_user_existence() {
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account().toBuilder()
                .status(Account.AccountStatus.SUSPENDED).build()));
        assertDenied(OWNER);
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account().toBuilder().ownerId(MEMBER.subjectId()).build()));
        assertDenied(OWNER);
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account()));
        Mockito.when(users.get(OWNER.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        assertDenied(OWNER);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void owner_can_manage_even_when_attached_policy_reads_are_unavailable() {
        Mockito.when(policies.listAttached(Mockito.any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
        Mockito.when(policies.create(Mockito.any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
        Policy created = service.createPolicy(OWNER, document());
        Assertions.assertThat(created.accountId()).isEqualTo(ACCOUNT);
        Assertions.assertThat(created.revision()).isEqualTo(1);
        Assertions.assertThat(created.document()).isEqualTo(document());
        Assertions.assertThat(PolicyValidator.isValidIdentifier(created.policyId())).isTrue();
        Assertions.assertThat(service.createPolicy(OWNER, document()).policyId()).isNotEqualTo(created.policyId());
        Mockito.verify(policies, Mockito.never()).listAttached(Mockito.any());
    }

    @Test
    void reads_use_callers_account_and_return_immutable_snapshots() {
        Policy policy = new Policy("p-1", ACCOUNT, 1, document());
        List<Policy> backing = new ArrayList<>(List.of(policy));
        Mockito.when(policies.get(ACCOUNT, "p-1")).thenReturn(found(policy));
        Mockito.when(policies.list(ACCOUNT)).thenReturn(CompletableFuture.completedFuture(backing));
        Mockito.when(policies.listAttached(TARGET)).thenReturn(CompletableFuture.completedFuture(backing));
        Assertions.assertThat(service.getPolicy(OWNER, "p-1")).isEqualTo(policy);
        List<Policy> listed = service.listPolicies(OWNER);
        List<Policy> attached = service.listAttachedPolicies(OWNER, TARGET);
        backing.clear();
        Assertions.assertThat(listed).containsExactly(policy);
        Assertions.assertThat(attached).containsExactly(policy);
        Assertions.assertThatThrownBy(listed::clear).isInstanceOf(UnsupportedOperationException.class);
        Assertions.assertThatThrownBy(attached::clear).isInstanceOf(UnsupportedOperationException.class);
        Mockito.when(policies.get(ACCOUNT, "missing")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Assertions.assertThatThrownBy(() -> service.getPolicy(OWNER, "missing")).isInstanceOf(PolicyNotFoundException.class);
    }

    @Test
    void invalid_documents_ids_and_revisions_never_reach_policy_storage() {
        PolicyDocument invalid = new PolicyDocument(2, document().statements());
        Assertions.assertThatThrownBy(() -> service.createPolicy(OWNER, invalid)).isInstanceOf(PolicyValidationException.class);
        Assertions.assertThatThrownBy(() -> service.updatePolicy(OWNER, "p-1", 1, invalid)).isInstanceOf(PolicyValidationException.class);
        Assertions.assertThatThrownBy(() -> service.getPolicy(OWNER, "*")).isInstanceOf(PolicyValidationException.class);
        Assertions.assertThatThrownBy(() -> service.updatePolicy(OWNER, "p-1", 0, document())).isInstanceOf(PolicyValidationException.class);
        Assertions.assertThatThrownBy(() -> service.deletePolicy(OWNER, "p-1", -1)).isInstanceOf(PolicyValidationException.class);
        Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 0, TARGET)).isInstanceOf(PolicyValidationException.class);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void cross_account_targets_are_denied_for_attach_detach_and_list() {
        PrincipalReference foreign = new PrincipalReference("a-other", TARGET.subjectId(), TARGET.subjectType());
        Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 1, foreign)).isInstanceOf(AuthorizationDeniedException.class);
        Assertions.assertThatThrownBy(() -> service.detachPolicy(OWNER, "p-1", foreign)).isInstanceOf(AuthorizationDeniedException.class);
        Assertions.assertThatThrownBy(() -> service.listAttachedPolicies(OWNER, foreign)).isInstanceOf(AuthorizationDeniedException.class);
        Mockito.verify(users, Mockito.never()).get(TARGET.subjectId(), true);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void attach_requires_current_same_account_user_and_usable_credential_with_creator() {
        Mockito.when(users.get(TARGET.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 1, TARGET)).isInstanceOf(PolicyNotFoundException.class);
        Mockito.when(users.get(TARGET.subjectId(), true)).thenReturn(found(user(TARGET.subjectId()).toBuilder().accountId("a-other").build()));
        Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 1, TARGET)).isInstanceOf(PolicyNotFoundException.class);
        for (Credential invalid : List.of(credential().toBuilder().revoked(true).build(),
                credential().toBuilder().createdByUserId(null).build())) {
            Mockito.when(credentials.get(CREDENTIAL.subjectId(), true)).thenReturn(found(invalid));
            Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 1, CREDENTIAL))
                    .isInstanceOf(PolicyValidationException.class);
        }
        Mockito.when(credentials.get(CREDENTIAL.subjectId(), true)).thenReturn(found(credential()));
        Mockito.when(users.get(TARGET.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 1, CREDENTIAL)).isInstanceOf(PolicyNotFoundException.class);
        Mockito.when(credentials.get(CREDENTIAL.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 1, CREDENTIAL)).isInstanceOf(PolicyNotFoundException.class);
        Mockito.when(credentials.get(CREDENTIAL.subjectId(), true)).thenReturn(found(credential().toBuilder().accountId("a-other").build()));
        Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 1, CREDENTIAL)).isInstanceOf(PolicyNotFoundException.class);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void mutations_delegate_revision_and_atomicity_to_policy_store() {
        Policy updated = new Policy("p-1", ACCOUNT, 8, document());
        Mockito.when(policies.update(ACCOUNT, "p-1", 7, document())).thenReturn(CompletableFuture.completedFuture(updated));
        Mockito.when(policies.attach(ACCOUNT, "p-1", 8, TARGET)).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(policies.attach(ACCOUNT, "p-1", 8, CREDENTIAL)).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(policies.delete(ACCOUNT, "p-1", 8)).thenReturn(CompletableFuture.completedFuture(null));
        Assertions.assertThat(service.updatePolicy(OWNER, "p-1", 7, document())).isEqualTo(updated);
        service.attachPolicy(OWNER, "p-1", 8, TARGET);
        service.attachPolicy(OWNER, "p-1", 8, CREDENTIAL);
        service.deletePolicy(OWNER, "p-1", 8);
        Mockito.verify(policies).attach(ACCOUNT, "p-1", 8, TARGET);
        Mockito.verify(policies).attach(ACCOUNT, "p-1", 8, CREDENTIAL);
        Mockito.verify(policies).delete(ACCOUNT, "p-1", 8);
    }

    @Test
    void detach_allows_deleted_targets_and_listing_allows_revoked_credentials() {
        Mockito.when(users.get(TARGET.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Mockito.when(credentials.get(CREDENTIAL.subjectId(), true)).thenReturn(found(credential().toBuilder().revoked(true).build()));
        Mockito.when(policies.detach(ACCOUNT, "p-1", TARGET)).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(policies.detach(ACCOUNT, "p-1", CREDENTIAL)).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(policies.listAttached(CREDENTIAL)).thenReturn(CompletableFuture.completedFuture(List.of()));
        service.detachPolicy(OWNER, "p-1", TARGET);
        service.detachPolicy(OWNER, "p-1", CREDENTIAL);
        Mockito.verify(users, Mockito.never()).get(TARGET.subjectId(), true);
        Mockito.verifyNoInteractions(credentials);
        Assertions.assertThat(service.listAttachedPolicies(OWNER, CREDENTIAL)).isEmpty();
        Assertions.assertThatThrownBy(() -> service.listAttachedPolicies(OWNER, TARGET)).isInstanceOf(PolicyNotFoundException.class);
        Mockito.verify(policies, Mockito.never()).listAttached(TARGET);
    }

    @Test
    void store_domain_errors_survive_and_infrastructure_failures_are_distinct() {
        for (RuntimeException error : List.of(new PolicyNotFoundException("missing"), new PolicyConflictException("stale"),
                new AuthorizationStoreException("unavailable", null))) {
            Mockito.when(policies.delete(ACCOUNT, "p-1", 1)).thenReturn(CompletableFuture.failedFuture(error));
            Assertions.assertThatThrownBy(() -> service.deletePolicy(OWNER, "p-1", 1)).isSameAs(error);
        }
        var unavailable = new IllegalStateException("offline");
        Mockito.when(policies.list(ACCOUNT)).thenThrow(unavailable);
        Assertions.assertThatThrownBy(() -> service.listPolicies(OWNER)).isInstanceOf(AuthorizationStoreException.class).hasCause(unavailable);
        Mockito.when(users.get(TARGET.subjectId(), true)).thenReturn(CompletableFuture.failedFuture(unavailable));
        Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 1, TARGET))
                .isInstanceOf(AuthorizationStoreException.class).hasCause(unavailable);
        Mockito.verify(policies, Mockito.never()).attach(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.any());
    }

    @Test
    void null_arguments_are_programming_errors() {
        Assertions.assertThatThrownBy(() -> service.createPolicy(null, document())).isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> service.createPolicy(OWNER, null)).isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> service.getPolicy(OWNER, null)).isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> service.attachPolicy(OWNER, "p-1", 1, null)).isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> service.listAttachedPolicies(OWNER, null)).isInstanceOf(NullPointerException.class);
        Mockito.verifyNoInteractions(policies);
    }

    private void assertDenied(AuthenticatedSession caller) {
        List<Consumer<AuthenticatedSession>> operations = List.of(
                value -> service.createPolicy(value, document()), value -> service.getPolicy(value, "p-1"),
                service::listPolicies, value -> service.updatePolicy(value, "p-1", 1, document()),
                value -> service.deletePolicy(value, "p-1", 1), value -> service.attachPolicy(value, "p-1", 1, TARGET),
                value -> service.detachPolicy(value, "p-1", TARGET), value -> service.listAttachedPolicies(value, TARGET));
        operations.forEach(operation -> Assertions.assertThatThrownBy(() -> operation.accept(caller))
                .isInstanceOf(AuthorizationDeniedException.class));
    }

    private static Account account() {
        return Account.builder().accountId(ACCOUNT).ownerId(OWNER.subjectId()).status(Account.AccountStatus.ACTIVE).build();
    }

    private static User user(String id) {
        return User.builder().userId(id).accountId(ACCOUNT).build();
    }

    private static Credential credential() {
        return Credential.builder().credentialId(CREDENTIAL.subjectId()).accountId(ACCOUNT)
                .createdByUserId(TARGET.subjectId()).revoked(false).build();
    }

    private static PolicyDocument document() {
        return new PolicyDocument(1, List.of(new PolicyDocument.Statement(PolicyDocument.Effect.ALLOW,
                List.of("instance:Start"), List.of("mc:instance:a-1:instance/*"))));
    }

    private static <T> CompletableFuture<Optional<T>> found(T value) {
        return CompletableFuture.completedFuture(Optional.of(value));
    }
}
