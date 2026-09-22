package jc121f1.services.authz.audit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.AuthorizationDecision.PolicyReference;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.authz.ResourceReference;
import jc121f1.services.authz.audit.AuthorizationAuditEvent.Kind;
import jc121f1.services.authz.audit.AuthorizationAuditEvent.Outcome;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import jc121f1.services.authz.exceptions.PolicyNotFoundException;
import jc121f1.services.authz.exceptions.PolicyValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.util.List;

/** Best-effort recording: sink failures must not mask denials or misreport committed mutations. */
public final class AuthorizationAudit {
    private static final Logger LOG = LoggerFactory.getLogger(AuthorizationAudit.class);
    private final Clock clock;
    private final AuthorizationAuditSink sink;

    @Inject
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected clock and audit sink are intentionally shared.")
    public AuthorizationAudit(Clock clock, AuthorizationAuditSink sink) {
        this.clock = clock;
        this.sink = sink;
    }

    public void decision(AuthenticatedSession caller, String action, ResourceReference resource, AuthorizationDecision decision) {
        Outcome outcome = decision.outcome() == AuthorizationDecision.Outcome.ALLOW ? Outcome.ALLOW : Outcome.DENY;
        record(Kind.DECISION, caller, action, resource, null, null, outcome, decision.reason().name(), decision.matchedPolicies());
    }

    public void success(AuthenticatedSession caller, String action, ResourceReference resource,
                        PrincipalReference target, Long expectedRevision, List<PolicyReference> policies) {
        record(Kind.POLICY_OPERATION, caller, action, resource, target, expectedRevision, Outcome.SUCCESS, "COMPLETED", policies);
    }

    public void failure(Kind kind, AuthenticatedSession caller, String action, ResourceReference resource,
                        PrincipalReference target, Long expectedRevision, RuntimeException error) {
        Outcome outcome = error instanceof AuthorizationDeniedException ? Outcome.DENY : Outcome.ERROR;
        record(kind, caller, action, resource, target, expectedRevision, outcome, reason(error), List.of());
    }

    private void record(Kind kind, AuthenticatedSession caller, String action, ResourceReference resource,
                         PrincipalReference target, Long revision, Outcome outcome, String reason, List<PolicyReference> policies) {
        try {
            PrincipalReference principal = caller == null ? null
                    : new PrincipalReference(caller.accountId(), caller.subjectId(), caller.subjectType());
            sink.record(new AuthorizationAuditEvent(clock.instant().toString(), kind, principal, action, resource,
                    target, revision, outcome, reason, policies));
        } catch (RuntimeException error) {
            // No exception text: sinks may include sensitive transport configuration in errors.
            LOG.error("Authorization audit event could not be recorded");
        }
    }

    private static String reason(RuntimeException error) {
        return switch (error) {
            case AuthorizationDeniedException ignored -> "ACCESS_DENIED";
            case PolicyValidationException ignored -> "INVALID_INPUT";
            case PolicyNotFoundException ignored -> "NOT_FOUND";
            case PolicyConflictException ignored -> "CONFLICT";
            case AuthorizationStoreException ignored -> "STORAGE_FAILURE";
            default -> "INTERNAL_ERROR";
        };
    }
}
