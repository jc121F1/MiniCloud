package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import jc121f1.model.auth.api.request.CreateUserRequest;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

/** Establishes trusted identity for protected routes; action checks live in AuthService. */
public class AuthAuthorizationHandler implements jc121f1.wbs.handlers.BaseHandler {
    private final AuthenticateHandler authenticateHandler;

    @Inject
    public AuthAuthorizationHandler(AuthenticateHandler authenticateHandler) {
        this.authenticateHandler = authenticateHandler;
    }

    @Override
    public void handle(@NotNull Context ctx) {
        AuthOperation operation = ctx.routeRoles().stream()
                .filter(AuthOperation.class::isInstance)
                .map(AuthOperation.class::cast)
                .findFirst().orElse(AuthOperation.PUBLIC);
        if (operation == AuthOperation.CREATE_USER) {
            if (ctx.bodyAsClass(CreateUserRequest.class).accountId() != null) {
                authenticateHandler.handle(ctx);
            }
        } else if (operation != AuthOperation.PUBLIC) {
            authenticateHandler.handle(ctx);
        }
    }
}
