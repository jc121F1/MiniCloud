package jc121f1.model.auth;

import io.javalin.http.Context;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.services.instance.exceptions.UnauthorizedException;

public final class AuthContext {
    private static final String KEY = "authenticatedSession";

    private AuthContext() { }

    public static void set(Context ctx, AuthenticatedSession session) {
        ctx.attribute(KEY, session);
    }

    public static AuthenticatedSession require(Context ctx) {
        AuthenticatedSession session = ctx.attribute(KEY);
        if (session == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return session;
    }
}
