package jc121f1.services.authz.audit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.authz.AuthorizationDecision.PolicyReference;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.authz.ResourceReference;
import jc121f1.model.authz.ServiceId;
import jc121f1.services.authz.PolicyService;
import jc121f1.services.authz.PolicyServiceImpl;
import jc121f1.services.authz.authorization.PolicyAction;
import jc121f1.services.authz.authorization.PolicyResourceType;

import javax.inject.Inject;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Records completed operations separately from authorization decisions, including failed mutations. */
public final class AuditedPolicyService implements PolicyService {
    private final PolicyServiceImpl delegate;
    private final AuthorizationAudit audit;

    @Inject
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected policy service and audit recorder are intentionally shared.")
    public AuditedPolicyService(PolicyServiceImpl delegate, AuthorizationAudit audit) {
        this.delegate = delegate;
        this.audit = audit;
    }

    @Override
    public Policy createPolicy(AuthenticatedSession caller, PolicyDocument document) {
        return record(caller, PolicyAction.CREATE_POLICY, account(caller), null, null,
                () -> delegate.createPolicy(caller, document), policy -> List.of(reference(policy)));
    }

    @Override
    public Policy getPolicy(AuthenticatedSession caller, String policyId) {
        return record(caller, PolicyAction.GET_POLICY, policy(caller, policyId), null, null,
                () -> delegate.getPolicy(caller, policyId), value -> List.of(reference(value)));
    }

    @Override
    public List<Policy> listPolicies(AuthenticatedSession caller) {
        return record(caller, PolicyAction.LIST_POLICIES, account(caller), null, null,
                () -> delegate.listPolicies(caller), AuditedPolicyService::references);
    }

    @Override
    public Policy updatePolicy(AuthenticatedSession caller, String policyId, long expectedRevision, PolicyDocument document) {
        return record(caller, PolicyAction.UPDATE_POLICY, policy(caller, policyId), null, expectedRevision,
                () -> delegate.updatePolicy(caller, policyId, expectedRevision, document), value -> List.of(reference(value)));
    }

    @Override
    public void deletePolicy(AuthenticatedSession caller, String policyId, long expectedRevision) {
        record(caller, PolicyAction.DELETE_POLICY, policy(caller, policyId), null, expectedRevision, () -> {
            delegate.deletePolicy(caller, policyId, expectedRevision);
            return null;
        }, ignored -> List.of(new PolicyReference(policyId, expectedRevision)));
    }

    @Override
    public void attachPolicy(AuthenticatedSession caller, String policyId, long expectedRevision, PrincipalReference principal) {
        record(caller, PolicyAction.ATTACH_POLICY, policy(caller, policyId), principal, expectedRevision, () -> {
            delegate.attachPolicy(caller, policyId, expectedRevision, principal);
            return null;
        }, ignored -> List.of(new PolicyReference(policyId, expectedRevision)));
    }

    @Override
    public void detachPolicy(AuthenticatedSession caller, String policyId, PrincipalReference principal) {
        record(caller, PolicyAction.DETACH_POLICY, policy(caller, policyId), principal, null, () -> {
            delegate.detachPolicy(caller, policyId, principal);
            return null;
        }, ignored -> List.of());
    }

    @Override
    public List<Policy> listAttachedPolicies(AuthenticatedSession caller, PrincipalReference principal) {
        PolicyResourceType type = principal != null && principal.subjectType() == Session.SubjectType.USER
                ? PolicyResourceType.USER : PolicyResourceType.CREDENTIAL;
        ResourceReference resource = target(caller, type, principal == null ? null : principal.subjectId());
        return record(caller, PolicyAction.LIST_ATTACHED_POLICIES, resource, principal, null,
                () -> delegate.listAttachedPolicies(caller, principal), AuditedPolicyService::references);
    }

    private <T> T record(AuthenticatedSession caller, PolicyAction action, ResourceReference resource,
                          PrincipalReference principal, Long expectedRevision, Supplier<T> operation,
                          Function<T, List<PolicyReference>> revisions) {
        T result;
        try {
            result = operation.get();
        } catch (RuntimeException error) {
            audit.failure(AuthorizationAuditEvent.Kind.POLICY_OPERATION, caller, action.value(), resource,
                    principal, expectedRevision, error);
            throw error;
        }
        audit.success(caller, action.value(), resource, principal, expectedRevision, revisions.apply(result));
        return result;
    }

    private static PolicyReference reference(Policy policy) {
        return new PolicyReference(policy.policyId(), policy.revision());
    }

    private static List<PolicyReference> references(List<Policy> policies) {
        return policies.stream().map(AuditedPolicyService::reference).toList();
    }

    private static ResourceReference account(AuthenticatedSession caller) {
        return target(caller, PolicyResourceType.ACCOUNT, caller == null ? null : caller.accountId());
    }

    private static ResourceReference policy(AuthenticatedSession caller, String id) {
        return target(caller, PolicyResourceType.POLICY, id);
    }

    private static ResourceReference target(AuthenticatedSession caller, PolicyResourceType type, String id) {
        return ResourceReference.of(ServiceId.AUTHZ, caller == null ? null : caller.accountId(), type, id);
    }
}
