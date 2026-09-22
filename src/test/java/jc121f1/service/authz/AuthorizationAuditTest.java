package jc121f1.service.authz;

import dagger.BindsInstance;
import dagger.Component;
import jc121f1.dagger.AuthorizationCatalogModule;
import jc121f1.dagger.AuthorizationModule;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.AuthorizationDecision.PolicyReference;
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
import jc121f1.services.authz.PolicyService;
import jc121f1.services.authz.PolicyServiceImpl;
import jc121f1.services.authz.PolicyValidator;
import jc121f1.services.authz.audit.AuditedAuthorizationService;
import jc121f1.services.authz.audit.AuditedPolicyService;
import jc121f1.services.authz.audit.AuthorizationAudit;
import jc121f1.services.authz.audit.AuthorizationAuditEvent;
import jc121f1.services.authz.audit.AuthorizationAuditEvent.Kind;
import jc121f1.services.authz.audit.AuthorizationAuditEvent.Outcome;
import jc121f1.services.authz.audit.AuthorizationAuditSink;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import jc121f1.services.authz.exceptions.PolicyNotFoundException;
import jc121f1.services.authz.exceptions.PolicyValidationException;
import jc121f1.services.authz.store.PolicyStore;
import jc121f1.services.instance.authorization.InstanceAction;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

class AuthorizationAuditTest {
    private static final AuthenticatedSession OWNER = new AuthenticatedSession("a-1", "u-owner", Session.SubjectType.USER);
    private static final AuthenticatedSession MEMBER = new AuthenticatedSession("a-1", "u-member", Session.SubjectType.USER);
    private static final PrincipalReference TARGET = new PrincipalReference("a-1", "u-member", Session.SubjectType.USER);
    private static final ResourceReference INSTANCE = new ResourceReference("instance", "a-1", "instance", "i-1");
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    private final List<AuthorizationAuditEvent> events = new ArrayList<>();
    private PolicyStore policies;
    private AuthorizationAuditSink sink;
    private AuthorizationService authorization;
    private PolicyService management;

    @BeforeEach
    void setup() {
        AccountStore accounts = Mockito.mock(AccountStore.class);
        UserStore users = Mockito.mock(UserStore.class);
        CredentialStore credentials = Mockito.mock(CredentialStore.class);
        policies = Mockito.mock(PolicyStore.class);
        sink = Mockito.mock(AuthorizationAuditSink.class);
        Mockito.doAnswer(call -> {
            events.add(call.getArgument(0));
            return null;
        }).when(sink).record(Mockito.any());
        Mockito.when(accounts.get("a-1", true)).thenReturn(CompletableFuture.completedFuture(Optional.of(
                Account.builder().accountId("a-1").ownerId("u-owner").status(Account.AccountStatus.ACTIVE).build())));
        for (AuthenticatedSession principal : List.of(OWNER, MEMBER)) {
            Mockito.when(users.get(principal.subjectId(), true)).thenReturn(CompletableFuture.completedFuture(Optional.of(
                    User.builder().userId(principal.subjectId()).accountId("a-1").passwordHash("private-hash").build())));
        }
        Mockito.when(policies.listAttached(Mockito.any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        var registry = AuthorizationCatalogModule.actionRegistry();
        var validator = new PolicyValidator(registry);
        var audit = new AuthorizationAudit(Clock.fixed(NOW, ZoneOffset.UTC), sink);
        authorization = new AuditedAuthorizationService(new AuthorizationServiceImpl(accounts, users, credentials, policies,
                registry, validator, new AuthorizationRules()), audit);
        management = new AuditedPolicyService(new PolicyServiceImpl(authorization, policies, validator, users, credentials), audit);
    }

    @Test
    void evaluate_and_authorize_emit_one_decision_each_with_revision_evidence() {
        Policy allowed = policy(4);
        Mockito.when(policies.listAttached(TARGET)).thenReturn(CompletableFuture.completedFuture(List.of(allowed)));
        AuthorizationDecision decision = authorization.evaluate(MEMBER, InstanceAction.START, INSTANCE);
        Assertions.assertThat(decision.outcome()).isEqualTo(AuthorizationDecision.Outcome.ALLOW);
        AuthorizationAuditEvent event = events.getFirst();
        Assertions.assertThat(event.kind()).isEqualTo(Kind.DECISION);
        Assertions.assertThat(event.outcome()).isEqualTo(Outcome.ALLOW);
        Assertions.assertThat(event.reason()).isEqualTo("POLICY_ALLOW");
        Assertions.assertThat(event.occurredAt()).isEqualTo(NOW.toString());
        Assertions.assertThat(event.caller()).isEqualTo(TARGET);
        Assertions.assertThat(event.action()).isEqualTo("instance:Start");
        Assertions.assertThat(event.resource()).isEqualTo(INSTANCE);
        Assertions.assertThat(event.policies()).containsExactly(new PolicyReference("p-1", 4));
        Mockito.when(policies.listAttached(TARGET)).thenReturn(CompletableFuture.completedFuture(List.of()));
        Assertions.assertThatThrownBy(() -> authorization.authorize(MEMBER, InstanceAction.START, INSTANCE))
                .isInstanceOf(AuthorizationDeniedException.class);
        Assertions.assertThat(events).hasSize(2);
        Assertions.assertThat(events.getLast().outcome()).isEqualTo(Outcome.DENY);
        Assertions.assertThat(events.getLast().reason()).isEqualTo("NO_MATCHING_ALLOW");
    }

    @Test
    void storage_failure_is_an_error_and_does_not_disclose_exception_text() {
        var failure = new AuthorizationStoreException("private connection string", new IllegalStateException("private token"));
        Mockito.when(policies.listAttached(TARGET)).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> authorization.evaluate(MEMBER, InstanceAction.START, INSTANCE)).isSameAs(failure);
        Assertions.assertThat(events).hasSize(1);
        Assertions.assertThat(events.getFirst().outcome()).isEqualTo(Outcome.ERROR);
        Assertions.assertThat(events.getFirst().reason()).isEqualTo("STORAGE_FAILURE");
        Assertions.assertThat(events.toString()).doesNotContain("private connection", "private token", "private-hash");
    }

    @Test
    void all_management_operations_record_completion_separately_from_owner_decision() {
        Mockito.when(policies.create(Mockito.any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
        Mockito.when(policies.get("a-1", "p-1")).thenReturn(CompletableFuture.completedFuture(Optional.of(policy(1))));
        Mockito.when(policies.list("a-1")).thenReturn(CompletableFuture.completedFuture(List.of(policy(1))));
        Mockito.when(policies.update("a-1", "p-1", 1, document())).thenReturn(CompletableFuture.completedFuture(policy(2)));
        Mockito.when(policies.attach("a-1", "p-1", 2, TARGET)).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(policies.detach("a-1", "p-1", TARGET)).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(policies.delete("a-1", "p-1", 2)).thenReturn(CompletableFuture.completedFuture(null));
        Policy created = management.createPolicy(OWNER, document());
        management.getPolicy(OWNER, "p-1");
        management.listPolicies(OWNER);
        management.updatePolicy(OWNER, "p-1", 1, document());
        management.attachPolicy(OWNER, "p-1", 2, TARGET);
        management.listAttachedPolicies(OWNER, TARGET);
        management.detachPolicy(OWNER, "p-1", TARGET);
        management.deletePolicy(OWNER, "p-1", 2);
        Assertions.assertThat(events).hasSize(16);
        for (int index = 0; index < events.size(); index += 2) {
            Assertions.assertThat(events.get(index).kind()).isEqualTo(Kind.DECISION);
            Assertions.assertThat(events.get(index).reason()).isEqualTo("OWNER_RECOVERY_ALLOW");
            Assertions.assertThat(events.get(index + 1).kind()).isEqualTo(Kind.POLICY_OPERATION);
            Assertions.assertThat(events.get(index + 1).outcome()).isEqualTo(Outcome.SUCCESS);
        }
        Assertions.assertThat(events.get(1).policies()).containsExactly(new PolicyReference(created.policyId(), 1));
        Assertions.assertThat(events.get(7).expectedRevision()).isEqualTo(1L);
        Assertions.assertThat(events.get(7).policies()).containsExactly(new PolicyReference("p-1", 2));
        Assertions.assertThat(events.get(9).attachmentTarget()).isEqualTo(TARGET);
        Assertions.assertThat(events.get(9).policies()).containsExactly(new PolicyReference("p-1", 2));
        Assertions.assertThat(events.get(13).action()).isEqualTo("authz:DetachPolicy");
        Assertions.assertThat(events.get(13).expectedRevision()).isNull();
        Assertions.assertThat(events.get(13).policies()).isEmpty();
        Assertions.assertThat(events.get(15).policies()).containsExactly(new PolicyReference("p-1", 2));
        Assertions.assertThat(events.toString()).doesNotContain("private-hash", "mc:instance:a-1:instance/*", "statements");
    }

    @Test
    void failed_mutations_never_emit_success_or_change_policy_storage() {
        Assertions.assertThatThrownBy(() -> management.createPolicy(MEMBER, document())).isInstanceOf(AuthorizationDeniedException.class);
        Assertions.assertThat(events.getLast().outcome()).isEqualTo(Outcome.DENY);
        Assertions.assertThat(events.getLast().reason()).isEqualTo("ACCESS_DENIED");
        Assertions.assertThatThrownBy(() -> management.createPolicy(OWNER, new PolicyDocument(2, document().statements())))
                .isInstanceOf(PolicyValidationException.class);
        Assertions.assertThat(events.getLast().reason()).isEqualTo("INVALID_INPUT");
        PrincipalReference foreign = new PrincipalReference("a-other", "u-other", Session.SubjectType.USER);
        Assertions.assertThatThrownBy(() -> management.attachPolicy(OWNER, "p-1", 1, foreign))
                .isInstanceOf(AuthorizationDeniedException.class);
        Assertions.assertThat(events.getLast().attachmentTarget()).isEqualTo(foreign);
        Assertions.assertThat(events.getLast().outcome()).isEqualTo(Outcome.DENY);
        Assertions.assertThat(events).noneMatch(event -> event.outcome() == Outcome.SUCCESS);
        Mockito.verifyNoInteractions(policies);
    }

    @Test
    void conflicts_and_missing_policies_preserve_errors_and_expected_revision() {
        var conflict = new PolicyConflictException("private detail");
        Mockito.when(policies.update("a-1", "p-1", 7, document())).thenReturn(CompletableFuture.failedFuture(conflict));
        Assertions.assertThatThrownBy(() -> management.updatePolicy(OWNER, "p-1", 7, document())).isSameAs(conflict);
        Assertions.assertThat(events.getFirst().outcome()).isEqualTo(Outcome.ALLOW);
        Assertions.assertThat(events.getLast().outcome()).isEqualTo(Outcome.ERROR);
        Assertions.assertThat(events.getLast().reason()).isEqualTo("CONFLICT");
        Assertions.assertThat(events.getLast().expectedRevision()).isEqualTo(7L);
        Assertions.assertThat(events.getLast().policies()).isEmpty();
        Mockito.when(policies.get("a-1", "p-1")).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        Assertions.assertThatThrownBy(() -> management.getPolicy(OWNER, "p-1")).isInstanceOf(PolicyNotFoundException.class);
        Assertions.assertThat(events.getLast().reason()).isEqualTo("NOT_FOUND");
        Assertions.assertThat(events.toString()).doesNotContain("private detail");
    }

    @Test
    void sink_failure_does_not_change_denials_committed_mutations_or_storage_errors() {
        Mockito.doThrow(new IllegalStateException("sink offline")).when(sink).record(Mockito.any());
        Assertions.assertThatThrownBy(() -> authorization.authorize(MEMBER, InstanceAction.START, INSTANCE))
                .isInstanceOf(AuthorizationDeniedException.class);
        Mockito.when(policies.create(Mockito.any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
        Assertions.assertThat(management.createPolicy(OWNER, document()).revision()).isEqualTo(1);
        Mockito.verify(policies).create(Mockito.any());
        var failure = new AuthorizationStoreException("offline", null);
        Mockito.when(policies.delete("a-1", "p-1", 1)).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(() -> management.deletePolicy(OWNER, "p-1", 1)).isSameAs(failure);
    }

    @Test
    void null_arguments_remain_programming_errors_and_record_no_success() {
        Assertions.assertThatThrownBy(() -> management.createPolicy(null, document())).isInstanceOf(NullPointerException.class);
        Assertions.assertThatThrownBy(() -> authorization.evaluate(null, InstanceAction.START, INSTANCE)).isInstanceOf(NullPointerException.class);
        Assertions.assertThat(events).hasSize(2).allMatch(event -> event.outcome() == Outcome.ERROR);
        Assertions.assertThat(events).allMatch(event -> event.reason().equals("INTERNAL_ERROR"));
    }

    @Test
    void production_bindings_resolve_both_audited_entry_points() {
        AuditComponent component = DaggerAuthorizationAuditTest_AuditComponent.factory().create(
                Mockito.mock(AccountStore.class), Mockito.mock(UserStore.class), Mockito.mock(CredentialStore.class),
                policies, Clock.fixed(NOW, ZoneOffset.UTC));
        Assertions.assertThat(component.authorization()).isInstanceOf(AuditedAuthorizationService.class);
        Assertions.assertThat(component.management()).isInstanceOf(AuditedPolicyService.class);
    }

    private static Policy policy(long revision) {
        return new Policy("p-1", "a-1", revision, document());
    }

    private static PolicyDocument document() {
        return new PolicyDocument(1, List.of(new PolicyDocument.Statement(PolicyDocument.Effect.ALLOW,
                List.of("instance:Start"), List.of("mc:instance:a-1:instance/*"))));
    }

    @Singleton
    @Component(modules = {AuthorizationModule.class, AuthorizationCatalogModule.class})
    interface AuditComponent {
        AuthorizationService authorization();

        PolicyService management();

        @Component.Factory
        interface Factory {
            AuditComponent create(@BindsInstance AccountStore accounts, @BindsInstance UserStore users,
                                  @BindsInstance CredentialStore credentials, @BindsInstance PolicyStore policies,
                                  @BindsInstance Clock clock);
        }
    }
}
