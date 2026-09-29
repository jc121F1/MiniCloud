package jc121f1.wbs.handlers.authz;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.auth.AuthContext;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.api.PolicyRequests;
import jc121f1.services.authz.PolicyService;
import jc121f1.services.authz.exceptions.PolicyValidationException;

import javax.inject.Inject;

/** Thin HTTP adapter; PolicyService enforces every operation's owner and account boundary. */
public final class PolicyHandlers {
    private final PolicyService service;

    @Inject
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "policy service is injected dependency and is intentionally shared."
    )
    public PolicyHandlers(PolicyService service) {
        this.service = service;
    }

    @OpenApi(path = "/policies/create", methods = HttpMethod.POST, operationId = "createAuthorizationPolicy",
            tags = {"Authorization"},
            requestBody = @OpenApiRequestBody(required = true, content = @OpenApiContent(from = PolicyRequests.CreatePolicyRequest.class)),
            responses = {
                    @OpenApiResponse(status = "201", content = @OpenApiContent(from = Policy.class)),
                    @OpenApiResponse(status = "400", description = "Invalid request or policy"),
                    @OpenApiResponse(status = "401", description = "Authentication required"),
                    @OpenApiResponse(status = "403", description = "Access denied"),
                    @OpenApiResponse(status = "404", description = "Policy or principal not found"),
                    @OpenApiResponse(status = "409", description = "Stale revision or attached policy"),
                    @OpenApiResponse(status = "413", description = "Request exceeds 64 KiB"),
                    @OpenApiResponse(status = "500", description = "Service unavailable")
            })
    public void create(Context ctx) {
        var request = body(ctx, PolicyRequests.CreatePolicyRequest.class);
        ctx.status(201).json(service.createPolicy(AuthContext.require(ctx), request.document()));
    }

    @OpenApi(path = "/policies/describe", methods = HttpMethod.POST, operationId = "getAuthorizationPolicy",
            tags = {"Authorization"},
            requestBody = @OpenApiRequestBody(required = true, content = @OpenApiContent(from = PolicyRequests.GetPolicyRequest.class)),
            responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(from = Policy.class)),
                    @OpenApiResponse(status = "400", description = "Invalid request or policy"),
                    @OpenApiResponse(status = "401", description = "Authentication required"),
                    @OpenApiResponse(status = "403", description = "Access denied"),
                    @OpenApiResponse(status = "404", description = "Policy or principal not found"),
                    @OpenApiResponse(status = "409", description = "Stale revision or attached policy"),
                    @OpenApiResponse(status = "413", description = "Request exceeds 64 KiB"),
                    @OpenApiResponse(status = "500", description = "Service unavailable")
            })
    public void get(Context ctx) {
        var request = body(ctx, PolicyRequests.GetPolicyRequest.class);
        ctx.json(service.getPolicy(AuthContext.require(ctx), request.policyId()));
    }

    @OpenApi(path = "/policies", methods = HttpMethod.GET, operationId = "listAuthorizationPolicy",
            tags = {"Authorization"},
            responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(from = Policy[].class)),
                    @OpenApiResponse(status = "400", description = "Invalid request or policy"),
                    @OpenApiResponse(status = "401", description = "Authentication required"),
                    @OpenApiResponse(status = "403", description = "Access denied"),
                    @OpenApiResponse(status = "404", description = "Policy or principal not found"),
                    @OpenApiResponse(status = "409", description = "Stale revision or attached policy"),
                    @OpenApiResponse(status = "413", description = "Request exceeds 64 KiB"),
                    @OpenApiResponse(status = "500", description = "Service unavailable")
            })
    public void list(Context ctx) {
        ctx.json(service.listPolicies(AuthContext.require(ctx)));
    }

    @OpenApi(path = "/policies/update", methods = HttpMethod.POST, operationId = "updateAuthorizationPolicy",
            tags = {"Authorization"},
            requestBody = @OpenApiRequestBody(required = true, content = @OpenApiContent(from = PolicyRequests.UpdatePolicyRequest.class)),
            responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(from = Policy.class)),
                    @OpenApiResponse(status = "400", description = "Invalid request or policy"),
                    @OpenApiResponse(status = "401", description = "Authentication required"),
                    @OpenApiResponse(status = "403", description = "Access denied"),
                    @OpenApiResponse(status = "404", description = "Policy or principal not found"),
                    @OpenApiResponse(status = "409", description = "Stale revision or attached policy"),
                    @OpenApiResponse(status = "413", description = "Request exceeds 64 KiB"),
                    @OpenApiResponse(status = "500", description = "Service unavailable")
            })
    public void update(Context ctx) {
        var request = body(ctx, PolicyRequests.UpdatePolicyRequest.class);
        ctx.json(service.updatePolicy(AuthContext.require(ctx), request.policyId(), request.expectedRevision(), request.document()));
    }

    @OpenApi(path = "/policies/delete", methods = HttpMethod.POST, operationId = "deleteAuthorizationPolicy",
            tags = {"Authorization"},
            requestBody = @OpenApiRequestBody(required = true, content = @OpenApiContent(from = PolicyRequests.DeletePolicyRequest.class)),
            responses = {
                    @OpenApiResponse(status = "204"),
                    @OpenApiResponse(status = "400", description = "Invalid request or policy"),
                    @OpenApiResponse(status = "401", description = "Authentication required"),
                    @OpenApiResponse(status = "403", description = "Access denied"),
                    @OpenApiResponse(status = "404", description = "Policy or principal not found"),
                    @OpenApiResponse(status = "409", description = "Stale revision or attached policy"),
                    @OpenApiResponse(status = "413", description = "Request exceeds 64 KiB"),
                    @OpenApiResponse(status = "500", description = "Service unavailable")
            })
    public void delete(Context ctx) {
        var request = body(ctx, PolicyRequests.DeletePolicyRequest.class);
        service.deletePolicy(AuthContext.require(ctx), request.policyId(), request.expectedRevision());
        ctx.status(204);
    }

    @OpenApi(path = "/policies/attach", methods = HttpMethod.POST, operationId = "attachAuthorizationPolicy",
            tags = {"Authorization"},
            requestBody = @OpenApiRequestBody(required = true, content = @OpenApiContent(from = PolicyRequests.AttachPolicyRequest.class)),
            responses = {
                    @OpenApiResponse(status = "204"),
                    @OpenApiResponse(status = "400", description = "Invalid request or policy"),
                    @OpenApiResponse(status = "401", description = "Authentication required"),
                    @OpenApiResponse(status = "403", description = "Access denied"),
                    @OpenApiResponse(status = "404", description = "Policy or principal not found"),
                    @OpenApiResponse(status = "409", description = "Stale revision or attached policy"),
                    @OpenApiResponse(status = "413", description = "Request exceeds 64 KiB"),
                    @OpenApiResponse(status = "500", description = "Service unavailable")
            })
    public void attach(Context ctx) {
        var request = body(ctx, PolicyRequests.AttachPolicyRequest.class);
        service.attachPolicy(AuthContext.require(ctx), request.policyId(), request.expectedRevision(), request.principal());
        ctx.status(204);
    }

    @OpenApi(path = "/policies/detach", methods = HttpMethod.POST, operationId = "detachAuthorizationPolicy",
            tags = {"Authorization"},
            requestBody = @OpenApiRequestBody(required = true, content = @OpenApiContent(from = PolicyRequests.DetachPolicyRequest.class)),
            responses = {
                    @OpenApiResponse(status = "204"),
                    @OpenApiResponse(status = "400", description = "Invalid request or policy"),
                    @OpenApiResponse(status = "401", description = "Authentication required"),
                    @OpenApiResponse(status = "403", description = "Access denied"),
                    @OpenApiResponse(status = "404", description = "Policy or principal not found"),
                    @OpenApiResponse(status = "409", description = "Stale revision or attached policy"),
                    @OpenApiResponse(status = "413", description = "Request exceeds 64 KiB"),
                    @OpenApiResponse(status = "500", description = "Service unavailable")
            })
    public void detach(Context ctx) {
        var request = body(ctx, PolicyRequests.DetachPolicyRequest.class);
        service.detachPolicy(AuthContext.require(ctx), request.policyId(), request.principal());
        ctx.status(204);
    }

    @OpenApi(path = "/policies/attachments/list", methods = HttpMethod.POST, operationId = "listAttachedAuthorizationPolicy",
            tags = {"Authorization"},
            requestBody = @OpenApiRequestBody(required = true, content = @OpenApiContent(from = PolicyRequests.ListAttachedPoliciesRequest.class)),
            responses = {
                    @OpenApiResponse(status = "200", content = @OpenApiContent(from = Policy[].class)),
                    @OpenApiResponse(status = "400", description = "Invalid request or policy"),
                    @OpenApiResponse(status = "401", description = "Authentication required"),
                    @OpenApiResponse(status = "403", description = "Access denied"),
                    @OpenApiResponse(status = "404", description = "Policy or principal not found"),
                    @OpenApiResponse(status = "409", description = "Stale revision or attached policy"),
                    @OpenApiResponse(status = "413", description = "Request exceeds 64 KiB"),
                    @OpenApiResponse(status = "500", description = "Service unavailable")
            })
    public void listAttached(Context ctx) {
        var request = body(ctx, PolicyRequests.ListAttachedPoliciesRequest.class);
        ctx.json(service.listAttachedPolicies(AuthContext.require(ctx), request.principal()));
    }

    private static <T> T body(Context ctx, Class<T> type) {
        // Enforce the byte limit before parsing; do not translate HTTP 413 into a JSON validation error.
        ctx.bodyAsBytes();
        T request;
        try {
            request = ctx.bodyAsClass(type);
        } catch (Exception error) {
            // Parser errors may quote request content. Return a fixed message.
            throw new PolicyValidationException("Invalid JSON request");
        }
        if (request == null) {
            throw new PolicyValidationException("Request body is required");
        }
        return request;
    }
}
