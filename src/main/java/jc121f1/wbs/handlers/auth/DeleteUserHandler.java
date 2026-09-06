package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.auth.api.request.DeleteUserRequest;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;


public class DeleteUserHandler extends AuthHandler {
    @Inject
    public DeleteUserHandler(AuthService authService) {
        super(authService);
    }

    @OpenApi(
            summary = "Delete specified user",
            operationId = "deleteUser",
            path = "/users/delete",
            methods = HttpMethod.POST,
            tags = {"User"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = DeleteUserRequest.class)}),
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = User.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        DeleteUserRequest request = ctx.bodyAsClass(DeleteUserRequest.class);

        User user = authService.deleteUser(request);

        ctx.json(user);
    }
}
