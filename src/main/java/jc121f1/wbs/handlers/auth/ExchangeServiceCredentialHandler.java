package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.auth.api.request.ExchangeServiceCredentialRequest;
import jc121f1.model.auth.dao.Session;
import jc121f1.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class ExchangeServiceCredentialHandler extends AuthHandler {
    @Inject
    public ExchangeServiceCredentialHandler(AuthService authService) {
        super(authService);
    }

    @OpenApi(
            summary = "ExchangeServiceCredential credential operation",
            operationId = "exchangeServiceCredential",
            path = "/credentials/exchange",
            methods = HttpMethod.POST,
            tags = {"Credential"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = ExchangeServiceCredentialRequest.class)}),
            responses = {@OpenApiResponse(status = "200", content = {@OpenApiContent(from = Session.class)})}
    )
    @Override
    public void handle(@NotNull Context ctx) {
        ExchangeServiceCredentialRequest request = ctx.bodyAsClass(ExchangeServiceCredentialRequest.class);
        ctx.json(authService.exchangeServiceCredential(request));
    }
}
