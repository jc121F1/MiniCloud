package jc121f1.services.authz;

import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;

import java.util.List;

/**
 * Account-scoped policy administration, independent of HTTP and persistence APIs.
 *
 * <p>Every operation requires the current authenticated owner user of the caller's
 * active account, including reads in V1. Implementations enforce this internally
 * before exposing policy data or changing storage. Credential callers and
 * cross-account targets are denied regardless of policy grants.
 *
 * <p>Policies and attachment targets must belong to the caller's account. Invalid
 * documents, missing targets, stale revisions, and attached-policy deletion fail
 * without changing storage. Permission denials throw AuthorizationDeniedException;
 * validation, not-found, conflict, and storage failures remain distinct errors.
 * All arguments are required; null arguments are caller programming errors.
 * Invalid input uses PolicyValidationException, missing targets use
 * PolicyNotFoundException, stale revisions or attached deletions use
 * PolicyConflictException, and persistence failures use AuthorizationStoreException.
 *
 * <p>Mutations are atomic. Deployment audit wrappers record outcomes on a best-effort
 * basis; audit delivery is not part of the storage transaction. A completed mutation must be visible to
 * subsequent authorization evaluations. No operation creates an identity.
 */
public interface PolicyService {
    /** Creates a validated policy in the caller's account, initially unattached. */
    Policy createPolicy(AuthenticatedSession caller, PolicyDocument document);

    /** Retrieves the current policy revision within the caller's account. */
    Policy getPolicy(AuthenticatedSession caller, String policyId);

    /** Returns an immutable snapshot of policies belonging to the caller's account. */
    List<Policy> listPolicies(AuthenticatedSession caller);

    /**
     * Replaces the entire document only if expectedRevision is current. Existing
     * attachments immediately follow the new revision; IDs and ownership are fixed.
     */
    Policy updatePolicy(AuthenticatedSession caller, String policyId,
                        long expectedRevision, PolicyDocument document);

    /**
     * Deletes only if the revision matches and no attachments exist. The attachment
     * check and deletion must be atomic with respect to concurrent attach operations.
     */
    void deletePolicy(AuthenticatedSession caller, String policyId, long expectedRevision);

    /**
     * Attaches a policy to an existing, usable same-account principal, only if the
     * policy revision matches. An existing attachment is an idempotent success after
     * validation. Attachments follow future revisions rather than pinning this one.
     */
    void attachPolicy(AuthenticatedSession caller, String policyId,
                      long expectedRevision, PrincipalReference principal);

    /**
     * Removes an attachment; an absent attachment is an idempotent success after
     * owner and policy checks. Allow cleanup even if the target identity was deleted
     * or revoked. Concurrent attach/detach operations take effect in their atomic
     * storage commit order.
     */
    void detachPolicy(AuthenticatedSession caller, String policyId, PrincipalReference principal);

    /** Returns immutable current policy snapshots for an existing principal, including a revoked credential. */
    List<Policy> listAttachedPolicies(AuthenticatedSession caller, PrincipalReference principal);
}
