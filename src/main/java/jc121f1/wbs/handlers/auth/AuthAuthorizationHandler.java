package jc121f1.wbs.handlers.auth;

import io.javalin.http.Context;
import jc121f1.model.auth.AuthContext;
import jc121f1.model.auth.api.request.CreateUserRequest;
import jc121f1.model.auth.api.request.DeleteUserRequest;
import jc121f1.model.auth.api.request.GetUserRequest;
import jc121f1.model.auth.api.request.InvalidateCredentialRequest;
import jc121f1.services.auth.AuthService;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.instance.exceptions.UnauthorizedException;
import jc121f1.services.instance.exceptions.ValidationException;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class AuthAuthorizationHandler extends AuthHandler {
    private final AuthenticateHandler authenticateHandler;
    private final CredentialStore credentialStore;

    @Inject
    public AuthAuthorizationHandler(AuthService authService, AuthenticateHandler authenticateHandler,
                                    CredentialStore credentialStore) {
        super(authService);
        this.authenticateHandler = authenticateHandler;
        this.credentialStore = credentialStore;
    }

    @Override
    public void handle(@NotNull Context ctx) {
        AuthOperation operation = ctx.routeRoles().stream()
                .filter(AuthOperation.class::isInstance)
                .map(AuthOperation.class::cast)
                .findFirst().orElse(AuthOperation.PUBLIC);
        switch (operation) {
            case CREATE_USER -> {
                var request = ctx.bodyAsClass(CreateUserRequest.class);
                if (request.accountId() != null) {
                    authenticateHandler.handle(ctx);
                    requireAccount(ctx, request.accountId());
                }
            }
            case DESCRIBE_USER -> {
                authenticateHandler.handle(ctx);
                var request = ctx.bodyAsClass(GetUserRequest.class);
                requireAccount(ctx, authService.getUser(request).accountId());
            }
            case DELETE_USER -> {
                authenticateHandler.handle(ctx);
                var request = ctx.bodyAsClass(DeleteUserRequest.class);
                var user = authService.getUser(new GetUserRequest(request.email(), request.userId()));
                requireAccount(ctx, user.accountId());
            }
            case INVALIDATE_CREDENTIAL -> {
                authenticateHandler.handle(ctx);
                var request = ctx.bodyAsClass(InvalidateCredentialRequest.class);
                if (request.credentialId() == null || request.credentialId().isBlank()) {
                    throw new ValidationException("credentialId is required");
                }
                var credential = credentialStore.get(request.credentialId()).join()
                        .orElseThrow(() -> new UnauthorizedException("Credential access denied"));
                requireAccount(ctx, credential.accountId());
            }
            default -> { }
        }
    }

    private void requireAccount(Context ctx, String accountId) {
        String callerAccountId = AuthContext.require(ctx).accountId();
        if (callerAccountId == null || !callerAccountId.equals(accountId)) {
            throw new UnauthorizedException("Account access denied");
        }
    }
}
