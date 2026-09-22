package jc121f1.dagger;

import dagger.Binds;
import dagger.Module;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.authz.AuthorizationServiceImpl;

import javax.inject.Singleton;

@Module
public abstract class AuthorizationModule {
    @Binds
    @Singleton
    public abstract AuthorizationService authorizationService(AuthorizationServiceImpl service);
}
