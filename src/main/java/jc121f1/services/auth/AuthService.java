package jc121f1.services.auth;

import jc121f1.model.auth.AuthenticateRequest;
import jc121f1.model.auth.api.request.CreateUserRequest;
import jc121f1.model.auth.api.request.DeleteUserRequest;
import jc121f1.model.auth.api.request.ExchangeServiceCredentialRequest;
import jc121f1.model.auth.api.request.GenerateCredentialRequest;
import jc121f1.model.auth.api.request.GetUserRequest;
import jc121f1.model.auth.api.request.InvalidateCredentialRequest;
import jc121f1.model.auth.api.request.LoginRequest;
import jc121f1.model.auth.dao.AuthenticatedSession;
import jc121f1.model.auth.dao.PublicFacingCredential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;

/**
 * Identity and credential lifecycle operations. Protected operations enforce authorization
 * at this boundary using a caller established by authentication.
 */
public interface AuthService {
    /** Create a new account and its first owner. Existing-account creation uses the caller overload. */
    User createUser(CreateUserRequest createUserRequest);
    User createUser(AuthenticatedSession caller, CreateUserRequest createUserRequest);
    User getUser(AuthenticatedSession caller, GetUserRequest getUserRequest);
    User deleteUser(AuthenticatedSession caller, DeleteUserRequest deleteUserRequest);
    Session login(LoginRequest loginRequest);
    Session exchangeServiceCredential(ExchangeServiceCredentialRequest request);
    PublicFacingCredential generateCredential(GenerateCredentialRequest request);
    void invalidateCredential(AuthenticatedSession caller, InvalidateCredentialRequest credential);
    AuthenticatedSession authenticate(AuthenticateRequest request);
}
