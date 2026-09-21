package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.auth.api.request.InvalidateCredentialRequest;
import jc121f1.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class InvalidateCredentialHandler extends AuthHandler {
    @Inject
    public InvalidateCredentialHandler(AuthService authService) {
        super(authService);
    }

    @OpenApi(
            summary = "InvalidateCredential credential operation",
            operationId = "invalidateCredential",
            path = "/credentials/invalidate",
            methods = HttpMethod.POST,
            tags = {"Credential"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = InvalidateCredentialRequest.class)}),
            responses = {@OpenApiResponse(status = "204")}
    )
    @Override
    public void handle(@NotNull Context ctx) {
        InvalidateCredentialRequest request = ctx.bodyAsClass(InvalidateCredentialRequest.class);
        authService.invalidateCredential(request);
        ctx.status(204);
    }
}
