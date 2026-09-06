package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.auth.api.request.LoginRequest;
import jc121f1.model.auth.dao.Session;
import jc121f1.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class LoginHandler extends AuthHandler {
    @Inject
    public LoginHandler(AuthService authService) {
        super(authService);
    }

    @OpenApi(
            summary = "Login to specified user",
            operationId = "login",
            path = "/users/login",
            methods = HttpMethod.POST,
            tags = {"User"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = LoginRequest.class)}),
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = Session.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        LoginRequest request = ctx.bodyAsClass(LoginRequest.class);

        Session user = authService.login(request);

        ctx.json(user);
    }
}
