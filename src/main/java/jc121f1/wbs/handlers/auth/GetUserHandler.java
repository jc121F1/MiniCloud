package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.auth.api.request.GetUserRequest;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;


public class GetUserHandler extends AuthHandler {
    @Inject
    public GetUserHandler(AuthService authService) {
        super(authService);
    }

    @OpenApi(
            summary = "Describe specified user",
            operationId = "describeUser",
            path = "/users/describe",
            methods = HttpMethod.POST,
            tags = {"User"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = GetUserRequest.class)}),
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = User.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        GetUserRequest request = ctx.bodyAsClass(GetUserRequest.class);

        User user = authService.getUser(request);

        ctx.json(user);
    }
}
