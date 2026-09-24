package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.auth.AuthContext;
import jc121f1.model.auth.api.request.TransferOwnershipRequest;
import jc121f1.model.auth.dao.Account;
import jc121f1.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public final class TransferOwnershipHandler extends AuthHandler {
    @Inject
    public TransferOwnershipHandler(AuthService authService) {
        super(authService);
    }

    @OpenApi(summary = "Transfer account ownership", operationId = "transferOwnership",
            path = "/users/transfer-ownership", methods = HttpMethod.POST, tags = {"User"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = TransferOwnershipRequest.class)}),
            responses = {@OpenApiResponse(status = "200", content = {@OpenApiContent(from = Account.class)})})
    @Override
    public void handle(@NotNull Context ctx) {
        TransferOwnershipRequest request = ctx.bodyAsClass(TransferOwnershipRequest.class);
        ctx.json(authService.transferOwnership(AuthContext.require(ctx), request));
    }
}
