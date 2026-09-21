package jc121f1.services.authz;

import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.ResourceReference;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;

/**
 * Account-scoped authorization, independent of HTTP and resource services.
 *
 * <p>Implementations resolve current identity and policy state on every evaluation.
 * Session identity is not a cached permission grant. Cross-account access is denied;
 * explicit denies override ordinary allows, and credentials are bounded by their
 * creator's current permissions. Owner-only operations follow the recovery rules
 * in docs/authz-design.md.
 *
 * <p>Completed policy changes must be visible to subsequent evaluations. Identity
 * or policy storage failures must propagate as service failures, never as allows
 * or ordinary permission denials. An allow does not reserve access or cancel work
 * already authorized when permissions subsequently change.
 */
public interface AuthorizationService {
    /**
     * Evaluates access without performing the protected operation.
     *
     * @param principal identity established by authentication, not client input
     * @param action exact, case-sensitive registered action, such as instance:Start;
     *               wildcards are only supported in policies
     * @param resource concrete target whose ownership is resolved by trusted code;
     *                 use an account resource for creation or collection actions
     * @return non-null decision; unknown actions and malformed resource references
     *         are denied, and no matching allow means deny
     * @throws NullPointerException if any argument is null (caller programming error)
     */
    AuthorizationDecision evaluate(AuthenticatedSession principal, String action, ResourceReference resource);

    /**
     * Applies the same evaluation rules and returns normally only when allowed.
     * Call before protected reads, mutations, or external side effects.
     *
     * @throws AuthorizationDeniedException if evaluation denies access
     * @throws NullPointerException if any argument is null
     */
    void authorize(AuthenticatedSession principal, String action, ResourceReference resource);
}
