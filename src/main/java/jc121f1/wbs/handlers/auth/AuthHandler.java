package jc121f1.wbs.handlers.auth;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.services.auth.AuthService;
import jc121f1.wbs.handlers.BaseHandler;


public abstract class AuthHandler implements BaseHandler {
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "InstanceService is an injected service dependency and is intentionally shared."
    )
    protected final AuthService authService;

    public AuthHandler(AuthService authService) {
        this.authService = authService;
    }
}
