package jc121f1.services.authz.audit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.authz.AuthorizationDecision;
import jc121f1.model.authz.ResourceReference;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.authz.AuthorizationServiceImpl;
import jc121f1.services.authz.exceptions.AuthorizationDeniedException;

import javax.inject.Inject;

/** Deployment entry point; the evaluator itself remains independent of audit delivery. */
public final class AuditedAuthorizationService implements AuthorizationService {
    private final AuthorizationServiceImpl delegate;
    private final AuthorizationAudit audit;

    @Inject
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected evaluator and audit recorder are intentionally shared.")
    public AuditedAuthorizationService(AuthorizationServiceImpl delegate, AuthorizationAudit audit) {
        this.delegate = delegate;
        this.audit = audit;
    }

    @Override
    public AuthorizationDecision evaluate(AuthenticatedSession principal, String action, ResourceReference resource) {
        AuthorizationDecision decision;
        try {
            decision = delegate.evaluate(principal, action, resource);
        } catch (RuntimeException error) {
            audit.failure(AuthorizationAuditEvent.Kind.DECISION, principal, action, resource, null, null, error);
            throw error;
        }
        audit.decision(principal, action, resource, decision);
        return decision;
    }

    @Override
    public void authorize(AuthenticatedSession principal, String action, ResourceReference resource) {
        if (evaluate(principal, action, resource).outcome() != AuthorizationDecision.Outcome.ALLOW) {
            throw new AuthorizationDeniedException();
        }
    }
}
