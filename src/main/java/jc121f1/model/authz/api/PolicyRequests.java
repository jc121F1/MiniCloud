package jc121f1.model.authz.api;

import io.javalin.openapi.OpenApiRequired;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.services.authz.exceptions.PolicyValidationException;

/** HTTP inputs never accept the caller's identity, account ownership, or a new policy ID. */
public final class PolicyRequests {
    private PolicyRequests() { }

    public record CreatePolicyRequest(@OpenApiRequired PolicyDocument document) {
        public CreatePolicyRequest {
            required(document, "document");
        }
    }

    public record GetPolicyRequest(@OpenApiRequired String policyId) {
        public GetPolicyRequest {
            required(policyId, "policyId");
        }
    }

    public record UpdatePolicyRequest(@OpenApiRequired String policyId, @OpenApiRequired long expectedRevision,
                                      @OpenApiRequired PolicyDocument document) {
        public UpdatePolicyRequest {
            required(policyId, "policyId");
            required(document, "document");
        }
    }

    public record DeletePolicyRequest(@OpenApiRequired String policyId, @OpenApiRequired long expectedRevision) {
        public DeletePolicyRequest {
            required(policyId, "policyId");
        }
    }

    public record AttachPolicyRequest(@OpenApiRequired String policyId, @OpenApiRequired long expectedRevision,
                                      @OpenApiRequired PrincipalReference principal) {
        public AttachPolicyRequest {
            required(policyId, "policyId");
            required(principal, "principal");
        }
    }

    public record DetachPolicyRequest(@OpenApiRequired String policyId, @OpenApiRequired PrincipalReference principal) {
        public DetachPolicyRequest {
            required(policyId, "policyId");
            required(principal, "principal");
        }
    }

    public record ListAttachedPoliciesRequest(@OpenApiRequired PrincipalReference principal) {
        public ListAttachedPoliciesRequest {
            required(principal, "principal");
        }
    }

    private static void required(Object value, String field) {
        if (value == null) {
            throw new PolicyValidationException(field + " is required");
        }
    }
}
