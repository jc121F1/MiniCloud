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
 * Identity and credential lifecycle operations. Callers must authorize account access,
 * user management before invoking these operations.
 * This service authenticates credentials and rejects inactive accounts; it does not
 * decide whether a caller is entitled to perform an action.
 */
public interface AuthService {
    User createUser(CreateUserRequest createUserRequest);
    User getUser(GetUserRequest getUserRequest);
    User deleteUser(DeleteUserRequest deleteUserRequest);
    Session login(LoginRequest loginRequest);
    Session exchangeServiceCredential(ExchangeServiceCredentialRequest request);
    PublicFacingCredential generateCredential(GenerateCredentialRequest request);
    void invalidateCredential(InvalidateCredentialRequest credential);
    AuthenticatedSession authenticate(AuthenticateRequest request);
}
