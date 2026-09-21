package jc121f1.model.authz;

import java.util.List;
import java.util.Objects;

/**
 * Internal evaluation result for enforcement and auditing, not an API response.
 * Matched policies include those used when evaluating a credential's creator.
 * Built-in rules and default denials may have no matched policies. Storage failures
 * are exceptions rather than decision reasons.
 */
public record AuthorizationDecision(Outcome outcome, Reason reason, List<PolicyReference> matchedPolicies) {
    public AuthorizationDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        matchedPolicies = List.copyOf(matchedPolicies);
    }

    public enum Outcome {
        ALLOW,
        DENY
    }

    /** Stable internal reason codes; clients must not depend on these details. */
    public enum Reason {
        POLICY_ALLOW,
        OWNER_ALLOW,
        OWNER_RECOVERY_ALLOW,
        EXPLICIT_DENY,
        NO_MATCHING_ALLOW,
        INVALID_PRINCIPAL,
        INACTIVE_ACCOUNT,
        CROSS_ACCOUNT,
        INVALID_ACTION,
        INVALID_RESOURCE,
        OWNER_REQUIRED,
        CREDENTIAL_OPERATION_FORBIDDEN,
        CREDENTIAL_CREATOR_MISSING,
        CREDENTIAL_CREATOR_DENIED
    }

    /** Identifies the exact persisted policy revision used in a decision. */
    public record PolicyReference(String policyId, long revision) {
        public PolicyReference {
            Objects.requireNonNull(policyId, "policyId");
        }
    }
}
