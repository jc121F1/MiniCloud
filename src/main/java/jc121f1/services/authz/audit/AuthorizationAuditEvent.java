package jc121f1.services.authz.audit;

import jc121f1.model.authz.AuthorizationDecision.PolicyReference;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.authz.ResourceReference;

import java.util.List;

/** Internal metadata only: never add policy documents, credentials, tokens, or exception text. */
public record AuthorizationAuditEvent(String occurredAt, Kind kind, PrincipalReference caller, String action,
                                      ResourceReference resource, PrincipalReference attachmentTarget,
                                      Long expectedRevision, Outcome outcome, String reason,
                                      List<PolicyReference> policies) {
    public AuthorizationAuditEvent {
        policies = List.copyOf(policies);
    }

    public enum Kind {
        DECISION,
        POLICY_OPERATION
    }

    public enum Outcome {
        ALLOW,
        DENY,
        SUCCESS,
        ERROR
    }
}
