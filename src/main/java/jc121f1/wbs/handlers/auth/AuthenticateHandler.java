package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import jc121f1.model.auth.AuthContext;
import jc121f1.model.auth.AuthenticateRequest;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.services.auth.AuthService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class AuthenticateHandler extends AuthHandler {
    @Inject
    public AuthenticateHandler(AuthService authService) {
        super(authService);
    }

    @Override
    public void handle(@NotNull Context ctx) {
        String authorization = ctx.header("Authorization");
        String bearerToken = ctx.header("BearerToken");
        if (authorization != null) {
            if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new jc121f1.services.instance.exceptions.UnauthorizedException("Invalid credentials");
            }
            bearerToken = authorization.substring(7).strip();
        }
        AuthenticateRequest request = AuthenticateRequest.builder().bearerToken(bearerToken).build();
        AuthenticatedSession session = authService.authenticate(request);

        AuthContext.set(ctx, session);
    }
}
