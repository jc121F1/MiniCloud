package jc121f1.service.authz;

import jc121f1.dagger.AuthorizationCatalogModule;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.AuthorizationDecision.Outcome;
import jc121f1.model.authz.AuthorizationDecision.PolicyReference;
import jc121f1.model.authz.AuthorizationDecision.Reason;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.authz.ResourceReference;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.authz.AuthorizationRules;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.authz.AuthorizationServiceImpl;
import jc121f1.services.authz.PolicyValidator;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.store.PolicyStore;
import jc121f1.services.instance.authorization.InstanceAction;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

class AuthorizationServiceTest {
    private static final String ACCOUNT = "a-1";
    private static final AuthenticatedSession USER = new AuthenticatedSession(ACCOUNT, "u-user", Session.SubjectType.USER);
    private static final AuthenticatedSession OWNER = new AuthenticatedSession(ACCOUNT, "u-owner", Session.SubjectType.USER);
    private static final AuthenticatedSession SERVICE = new AuthenticatedSession(ACCOUNT, "cre-1", Session.SubjectType.CREDENTIAL);
    private static final ResourceReference INSTANCE = new ResourceReference("instance", ACCOUNT, "instance", "i-1");
    private static final ResourceReference POLICY = new ResourceReference("authz", ACCOUNT, "policy", "p-1");
    private AccountStore accounts;
    private UserStore users;
    private CredentialStore credentials;
    private PolicyStore policies;
    private AuthorizationService service;

    @BeforeEach
    void setup() {
        accounts = Mockito.mock(AccountStore.class);
        users = Mockito.mock(UserStore.class);
        credentials = Mockito.mock(CredentialStore.class);
        policies = Mockito.mock(PolicyStore.class);
        var registry = AuthorizationCatalogModule.actionRegistry();
        service = new AuthorizationServiceImpl(accounts, users, credentials, policies, registry,
                new PolicyValidator(registry), new AuthorizationRules());
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account(Account.AccountStatus.ACTIVE)));
        Mockito.when(users.get(USER.subjectId(), true)).thenReturn(found(user(USER.subjectId())));
        Mockito.when(users.get(OWNER.subjectId(), true)).thenReturn(found(user(OWNER.subjectId())));
        Mockito.when(credentials.get(SERVICE.subjectId(), true)).thenReturn(found(credential(USER.subjectId())));
        Mockito.when(policies.listAttached(Mockito.any())).thenReturn(CompletableFuture.completedFuture(List.of()));
    }

    @Test
    void defaults_to_deny_and_enforcement_throws_without_policy_details() {
        assertDecision(start(USER), Outcome.DENY, Reason.NO_MATCHING_ALLOW);
        Assertions.assertThatThrownBy(() -> service.authorize(USER, InstanceAction.START, INSTANCE))
                .isInstanceOf(AuthorizationDeniedException.class).hasMessage("Access denied");
        attach(USER, allow());
        Assertions.assertThatCode(() -> service.authorize(USER, InstanceAction.START, INSTANCE)).doesNotThrowAnyException();
    }

    @Test
    void exact_and_wildcard_grants_preserve_resource_boundaries() {
        attach(USER, policy("p-exact", 1, PolicyDocument.Effect.ALLOW, "instance:Start", "mc:instance:a-1:instance/i-1"));
        assertDecision(start(USER), Outcome.ALLOW, Reason.POLICY_ALLOW);
        assertDecision(service.evaluate(USER, "instance:Start", new ResourceReference("instance", ACCOUNT, "instance", "i-12")),
                Outcome.DENY, Reason.NO_MATCHING_ALLOW);
        attach(USER, policy("p-wild", 1, PolicyDocument.Effect.ALLOW, "instance:*", "mc:instance:a-1:instance/*"));
        assertDecision(service.evaluate(USER, "instance:Stop", INSTANCE), Outcome.ALLOW, Reason.POLICY_ALLOW);
        assertDecision(service.evaluate(USER, "instance:Create", new ResourceReference("instance", ACCOUNT, "account", ACCOUNT)),
                Outcome.DENY, Reason.NO_MATCHING_ALLOW);
    }

    @Test
    void explicit_deny_wins_in_either_order_and_reports_matched_revisions() {
        Policy denied = policy("p-deny", 4, PolicyDocument.Effect.DENY, "instance:*", "mc:instance:a-1:instance/*");
        for (List<Policy> order : List.of(List.of(allow(), denied), List.of(denied, allow()))) {
            attach(USER, order.toArray(Policy[]::new));
            AuthorizationDecision decision = start(USER);
            assertDecision(decision, Outcome.DENY, Reason.EXPLICIT_DENY);
            Assertions.assertThat(decision.matchedPolicies()).containsExactly(new PolicyReference("p-allow", 1), new PolicyReference("p-deny", 4));
        }
        attach(USER, allow(), policy("p-unrelated", 1, PolicyDocument.Effect.DENY, "instance:Stop", "mc:instance:a-1:instance/*"));
        assertDecision(start(USER), Outcome.ALLOW, Reason.POLICY_ALLOW);
    }

    @Test
    void owner_gets_ordinary_access_but_explicit_denies_apply() {
        assertDecision(start(OWNER), Outcome.ALLOW, Reason.OWNER_ALLOW);
        attach(OWNER, policy("p-deny", 1, PolicyDocument.Effect.DENY, "instance:Start", "mc:instance:a-1:instance/*"));
        assertDecision(start(OWNER), Outcome.DENY, Reason.EXPLICIT_DENY);
    }

    @Test
    void only_current_owner_can_use_recovery_operations_and_policy_grants_cannot_delegate_them() {
        attach(USER, policy("p-admin", 1, PolicyDocument.Effect.ALLOW, "authz:*", "mc:authz:a-1:policy/*"));
        assertDecision(service.evaluate(USER, "authz:DeletePolicy", POLICY), Outcome.DENY, Reason.OWNER_REQUIRED);
        Mockito.when(policies.listAttached(reference(OWNER))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        assertDecision(service.evaluate(OWNER, "authz:DeletePolicy", POLICY), Outcome.ALLOW, Reason.OWNER_RECOVERY_ALLOW);
        Mockito.verify(policies, Mockito.never()).listAttached(reference(OWNER));
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account(Account.AccountStatus.ACTIVE).toBuilder()
                .ownerId(USER.subjectId()).build()));
        assertDecision(service.evaluate(OWNER, "authz:DeletePolicy", POLICY), Outcome.DENY, Reason.OWNER_REQUIRED);
        assertDecision(service.evaluate(USER, "authz:DeletePolicy", POLICY), Outcome.ALLOW, Reason.OWNER_RECOVERY_ALLOW);
    }

    @Test
    void malformed_and_cross_account_requests_cannot_use_owner_privileges() {
        assertDecision(service.evaluate(OWNER, "instance:*", INSTANCE), Outcome.DENY, Reason.INVALID_ACTION);
        assertDecision(service.evaluate(OWNER, "instance:start", INSTANCE), Outcome.DENY, Reason.INVALID_ACTION);
        assertDecision(service.evaluate(OWNER, "instance:Start", new ResourceReference("instance", ACCOUNT, "account", ACCOUNT)),
                Outcome.DENY, Reason.INVALID_RESOURCE);
        assertDecision(service.evaluate(OWNER, "instance:Start", new ResourceReference("instance", ACCOUNT, "instance", "*")),
                Outcome.DENY, Reason.INVALID_RESOURCE);
        assertDecision(service.evaluate(OWNER, "authz:DeletePolicy", new ResourceReference("authz", "a-other", "policy", "p-1")),
                Outcome.DENY, Reason.CROSS_ACCOUNT);
        assertDecision(start(new AuthenticatedSession(ACCOUNT, null, Session.SubjectType.USER)), Outcome.DENY, Reason.INVALID_PRINCIPAL);
        Mockito.verifyNoInteractions(accounts, users, credentials, policies);
    }

    @Test
    void deleted_or_mismatched_users_and_inactive_accounts_are_denied() {
        for (Account.AccountStatus status : List.of(Account.AccountStatus.SUSPENDED, Account.AccountStatus.CLOSED)) {
            Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account(status)));
            assertDecision(start(OWNER), Outcome.DENY, Reason.INACTIVE_ACCOUNT);
            assertDecision(service.evaluate(OWNER, "authz:DeletePolicy", POLICY), Outcome.DENY, Reason.INACTIVE_ACCOUNT);
        }
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        assertDecision(start(OWNER), Outcome.DENY, Reason.INACTIVE_ACCOUNT);
        Mockito.when(accounts.get(ACCOUNT, true)).thenReturn(found(account(Account.AccountStatus.ACTIVE)));
        Mockito.when(users.get(USER.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        assertDecision(start(USER), Outcome.DENY, Reason.INVALID_PRINCIPAL);
        Mockito.when(users.get(USER.subjectId(), true)).thenReturn(found(user(USER.subjectId()).toBuilder().accountId("a-other").build()));
        assertDecision(start(USER), Outcome.DENY, Reason.INVALID_PRINCIPAL);
        Mockito.when(users.get(OWNER.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        assertDecision(service.evaluate(OWNER, "authz:DeletePolicy", POLICY), Outcome.DENY, Reason.INVALID_PRINCIPAL);
    }

    @Test
    void credentials_need_own_grant_and_creators_current_permission() {
        attach(USER, allow());
        assertDecision(start(SERVICE), Outcome.DENY, Reason.NO_MATCHING_ALLOW);
        attach(SERVICE, policy("p-service", 2, PolicyDocument.Effect.ALLOW, "instance:Start", "mc:instance:a-1:instance/*"));
        assertDecision(start(SERVICE), Outcome.ALLOW, Reason.POLICY_ALLOW);
        Assertions.assertThat(start(SERVICE).matchedPolicies()).containsExactly(new PolicyReference("p-allow", 1), new PolicyReference("p-service", 2));
        attach(USER);
        assertDecision(start(SERVICE), Outcome.DENY, Reason.CREDENTIAL_CREATOR_DENIED);
        attach(USER, policy("p-deny", 3, PolicyDocument.Effect.DENY, "instance:Start", "mc:instance:a-1:instance/*"));
        assertDecision(start(SERVICE), Outcome.DENY, Reason.CREDENTIAL_CREATOR_DENIED);
        Assertions.assertThat(start(SERVICE).matchedPolicies()).contains(new PolicyReference("p-deny", 3));
    }

    @Test
    void owners_credentials_do_not_inherit_unbounded_access_or_recovery_privileges() {
        Mockito.when(credentials.get(SERVICE.subjectId(), true)).thenReturn(found(credential(OWNER.subjectId())));
        assertDecision(start(SERVICE), Outcome.DENY, Reason.NO_MATCHING_ALLOW);
        attach(SERVICE, allow());
        assertDecision(start(SERVICE), Outcome.ALLOW, Reason.POLICY_ALLOW);
        assertDecision(service.evaluate(SERVICE, "authz:DeletePolicy", POLICY), Outcome.DENY, Reason.CREDENTIAL_OPERATION_FORBIDDEN);
        assertDecision(service.evaluate(SERVICE, "auth:GenerateCredential", new ResourceReference("auth", ACCOUNT, "account", ACCOUNT)),
                Outcome.DENY, Reason.CREDENTIAL_OPERATION_FORBIDDEN);
        attach(OWNER, policy("p-deny", 1, PolicyDocument.Effect.DENY, "instance:Start", "mc:instance:a-1:instance/*"));
        assertDecision(start(SERVICE), Outcome.DENY, Reason.CREDENTIAL_CREATOR_DENIED);
    }

    @Test
    void credential_explicit_deny_wins_even_when_creator_allows() {
        attach(USER, allow());
        attach(SERVICE, allow(), policy("p-deny", 1, PolicyDocument.Effect.DENY, "instance:Start", "mc:instance:a-1:instance/*"));
        assertDecision(start(SERVICE), Outcome.DENY, Reason.EXPLICIT_DENY);
    }

    @Test
    void revoked_deleted_legacy_and_orphaned_credentials_are_denied() {
        attach(SERVICE, allow());
        attach(USER, allow());
        Mockito.when(credentials.get(SERVICE.subjectId(), true)).thenReturn(found(credential(USER.subjectId()).toBuilder().revoked(true).build()));
        assertDecision(start(SERVICE), Outcome.DENY, Reason.INVALID_PRINCIPAL);
        Mockito.when(credentials.get(SERVICE.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        assertDecision(start(SERVICE), Outcome.DENY, Reason.INVALID_PRINCIPAL);
        Mockito.when(credentials.get(SERVICE.subjectId(), true)).thenReturn(found(credential(null)));
        assertDecision(start(SERVICE), Outcome.DENY, Reason.CREDENTIAL_CREATOR_MISSING);
        Mockito.when(credentials.get(SERVICE.subjectId(), true)).thenReturn(found(credential(USER.subjectId())));
        Mockito.when(users.get(USER.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        assertDecision(start(SERVICE), Outcome.DENY, Reason.CREDENTIAL_CREATOR_MISSING);
        Mockito.when(users.get(USER.subjectId(), true)).thenReturn(found(user(USER.subjectId()).toBuilder().accountId("a-other").build()));
        assertDecision(start(SERVICE), Outcome.DENY, Reason.CREDENTIAL_CREATOR_MISSING);
    }

    @Test
    void mismatched_credentials_are_denied_before_creator_or_policy_reads() {
        for (Credential invalid : List.of(credential(USER.subjectId()).toBuilder().accountId("a-other").build(),
                credential(USER.subjectId()).toBuilder().credentialId("cre-other").build())) {
            Mockito.when(credentials.get(SERVICE.subjectId(), true)).thenReturn(found(invalid));
            assertDecision(start(SERVICE), Outcome.DENY, Reason.INVALID_PRINCIPAL);
        }
        Mockito.verifyNoInteractions(users, policies);
    }

    @Test
    void credential_identity_checks_precede_operation_restrictions_and_policy_reads() {
        Mockito.when(credentials.get(SERVICE.subjectId(), true)).thenReturn(found(credential(null)));
        assertDecision(service.evaluate(SERVICE, "authz:DeletePolicy", POLICY), Outcome.DENY, Reason.CREDENTIAL_CREATOR_MISSING);
        Mockito.when(credentials.get(SERVICE.subjectId(), true)).thenReturn(found(credential(USER.subjectId())));
        assertDecision(service.evaluate(SERVICE, "authz:DeletePolicy", POLICY), Outcome.DENY, Reason.CREDENTIAL_OPERATION_FORBIDDEN);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void decisions_recheck_completed_changes_with_consistent_identity_reads() {
        attach(USER, allow());
        assertDecision(start(USER), Outcome.ALLOW, Reason.POLICY_ALLOW);
        attach(USER);
        assertDecision(start(USER), Outcome.DENY, Reason.NO_MATCHING_ALLOW);
        Mockito.verify(accounts, Mockito.times(2)).get(ACCOUNT, true);
        Mockito.verify(users, Mockito.times(2)).get(USER.subjectId(), true);
        Mockito.verify(accounts, Mockito.never()).get(Mockito.anyString());
        Mockito.verify(users, Mockito.never()).get(Mockito.anyString());
    }

    @Test
    void storage_failures_propagate_from_evaluation_and_enforcement() {
        Mockito.when(policies.listAttached(reference(USER))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        Assertions.assertThatThrownBy(() -> start(USER)).isInstanceOf(AuthorizationStoreException.class);
        Assertions.assertThatThrownBy(() -> service.authorize(USER, InstanceAction.START, INSTANCE)).isInstanceOf(AuthorizationStoreException.class);
        Mockito.when(accounts.get(ACCOUNT, true)).thenThrow(new IllegalStateException("closed client"));
        Assertions.assertThatThrownBy(() -> start(OWNER)).isInstanceOf(AuthorizationStoreException.class);
    }

    @Test
    void malformed_stored_policies_fail_closed_including_for_owners() {
        for (Policy invalid : List.of(
                new Policy("p-other", "a-other", 1, allow().document()),
                policy("p-invalid", 1, PolicyDocument.Effect.ALLOW, "instance:St*", "mc:instance:a-1:instance/*"),
                new Policy("p-bad", ACCOUNT, 0, allow().document()),
                new Policy("p-null", ACCOUNT, 1, null))) {
            attach(OWNER, invalid);
            Assertions.assertThatThrownBy(() -> start(OWNER)).isInstanceOf(AuthorizationStoreException.class);
        }
    }

    @Test
    void current_owner_cannot_be_deleted_even_with_an_explicit_grant() {
        ResourceReference owner = new ResourceReference("auth", ACCOUNT, "user", OWNER.subjectId());
        attach(USER, policy("p-delete", 1, PolicyDocument.Effect.ALLOW, "auth:DeleteUser", "mc:auth:a-1:user/*"));
        assertDecision(service.evaluate(USER, "auth:DeleteUser", owner), Outcome.DENY, Reason.ACCOUNT_OWNER_PROTECTED);
        assertDecision(service.evaluate(OWNER, "auth:DeleteUser", owner), Outcome.DENY, Reason.ACCOUNT_OWNER_PROTECTED);
    }

    @Test
    void null_arguments_are_programming_errors() {
        Assertions.assertThatThrownBy(() -> service.evaluate(null, "instance:Start", INSTANCE)).isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> service.evaluate(USER, (String) null, INSTANCE)).isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> service.evaluate(USER, "instance:Start", null)).isInstanceOf(NullPointerException.class);
    }

    private AuthorizationDecision start(AuthenticatedSession principal) {
        return service.evaluate(principal, InstanceAction.START, INSTANCE);
    }

    private void attach(AuthenticatedSession principal, Policy... attached) {
        Mockito.when(policies.listAttached(reference(principal))).thenReturn(CompletableFuture.completedFuture(List.of(attached)));
    }

    private static PrincipalReference reference(AuthenticatedSession principal) {
        return new PrincipalReference(principal.accountId(), principal.subjectId(), principal.subjectType());
    }

    private static Policy allow() {
        return policy("p-allow", 1, PolicyDocument.Effect.ALLOW, "instance:Start", "mc:instance:a-1:instance/*");
    }

    private static Policy policy(String id, long revision, PolicyDocument.Effect effect, String action, String resource) {
        return new Policy(id, ACCOUNT, revision, new PolicyDocument(1,
                List.of(new PolicyDocument.Statement(effect, List.of(action), List.of(resource)))));
    }

    private static Account account(Account.AccountStatus status) {
        return Account.builder().accountId(ACCOUNT).ownerId(OWNER.subjectId()).status(status).build();
    }

    private static User user(String id) {
        return User.builder().userId(id).accountId(ACCOUNT).build();
    }

    private static Credential credential(String creator) {
        return Credential.builder().credentialId(SERVICE.subjectId()).accountId(ACCOUNT).createdByUserId(creator).revoked(false).build();
    }

    private static <T> CompletableFuture<Optional<T>> found(T value) {
        return CompletableFuture.completedFuture(Optional.of(value));
    }

    private static void assertDecision(AuthorizationDecision decision, Outcome outcome, Reason reason) {
        Assertions.assertThat(decision.outcome()).isEqualTo(outcome);
        Assertions.assertThat(decision.reason()).isEqualTo(reason);
    }
}
