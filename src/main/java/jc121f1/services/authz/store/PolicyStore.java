package jc121f1.services.authz.store;

import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Trusted persistence boundary, not an authorization entry point. PolicyService
 * authorizes callers and validates documents before invoking mutations.
 * Reads observe completed mutations; concurrent reads are not multi-item snapshots.
 * Mutations use atomic revision checks and attachment bookkeeping. Deleted policy
 * IDs cannot be reused. Implementations distinguish missing, conflicting, and
 * unavailable storage through the authz exception types.
 */
public interface PolicyStore {
    CompletableFuture<Policy> create(Policy policy);

    CompletableFuture<Optional<Policy>> get(String accountId, String policyId);

    CompletableFuture<List<Policy>> list(String accountId);

    CompletableFuture<Policy> update(String accountId, String policyId, long expectedRevision, PolicyDocument document);

    CompletableFuture<Void> delete(String accountId, String policyId, long expectedRevision);

    CompletableFuture<Void> attach(String accountId, String policyId, long expectedRevision, PrincipalReference principal);

    CompletableFuture<Void> detach(String accountId, String policyId, PrincipalReference principal);

    /** Missing policies behind attachments are storage errors, never implicit grants. */
    CompletableFuture<List<Policy>> listAttached(PrincipalReference principal);
}
