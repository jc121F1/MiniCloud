package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.auth.api.request.CreateUserRequest;
import jc121f1.model.auth.AuthContext;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;


public class CreateUserHandler extends AuthHandler {
    @Inject
    public CreateUserHandler(AuthService authService) {
        super(authService);
    }

    @OpenApi(
            summary = "Create user",
            operationId = "createUser",
            path = "/users/create",
            methods = HttpMethod.POST,
            tags = {"User"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = CreateUserRequest.class)}),
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = User.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        CreateUserRequest request = ctx.bodyAsClass(CreateUserRequest.class);

        User user = request.accountId() == null ? authService.createUser(request)
                : authService.createUser(AuthContext.require(ctx), request);

        ctx.json(user);
    }
}
