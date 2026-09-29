package jc121f1.dagger.auth;

import jc121f1.wbs.handlers.auth.CreateUserHandler;
import jc121f1.wbs.handlers.auth.GetUserHandler;
import jc121f1.wbs.handlers.auth.DeleteUserHandler;
import jc121f1.wbs.handlers.auth.LoginHandler;
import jc121f1.wbs.handlers.auth.GenerateCredentialHandler;
import jc121f1.wbs.handlers.auth.ExchangeServiceCredentialHandler;
import jc121f1.wbs.handlers.auth.InvalidateCredentialHandler;
import jc121f1.wbs.handlers.auth.AuthAuthorizationHandler;
import jc121f1.wbs.handlers.auth.TransferOwnershipHandler;
import jc121f1.wbs.handlers.RootHandler;

public interface AuthWebServiceHandlers extends AuthHandlers {
    RootHandler rootHandler();
    CreateUserHandler createUserHandler();
    GetUserHandler getUserHandler();
    DeleteUserHandler deleteUserHandler();
    LoginHandler loginHandler();
    GenerateCredentialHandler generateCredentialHandler();
    ExchangeServiceCredentialHandler exchangeServiceCredentialHandler();
    InvalidateCredentialHandler invalidateCredentialHandler();
    AuthAuthorizationHandler authAuthorizationHandler();
    TransferOwnershipHandler transferOwnershipHandler();
}
