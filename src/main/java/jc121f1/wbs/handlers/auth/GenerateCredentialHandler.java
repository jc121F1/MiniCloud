package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.auth.api.request.GenerateCredentialRequest;
import jc121f1.model.auth.dao.PublicFacingCredential;
import jc121f1.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class GenerateCredentialHandler extends AuthHandler {
    @Inject
    public GenerateCredentialHandler(AuthService authService) {
        super(authService);
    }

    @OpenApi(
            summary = "GenerateCredential credential operation",
            operationId = "generateCredential",
            path = "/credentials/generate",
            methods = HttpMethod.POST,
            tags = {"Credential"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = GenerateCredentialRequest.class)}),
            responses = {@OpenApiResponse(status = "200", content = {@OpenApiContent(from = PublicFacingCredential.class)})}
    )
    @Override
    public void handle(@NotNull Context ctx) {
        GenerateCredentialRequest request = ctx.bodyAsClass(GenerateCredentialRequest.class);
        ctx.json(authService.generateCredential(request));
    }
}
