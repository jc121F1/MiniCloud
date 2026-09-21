package jc121f1.model.authz;

/**
 * Persisted policy snapshot. IDs are server-generated and never reused; account
 * ownership is immutable. Revisions start at 1 and increment on each replacement.
 */
public record Policy(String policyId, String accountId, long revision, PolicyDocument document) {
}
