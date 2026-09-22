package jc121f1.dagger;

import dagger.Binds;
import dagger.Module;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.authz.AuthorizationServiceImpl;
import jc121f1.services.authz.PolicyService;
import jc121f1.services.authz.PolicyServiceImpl;

import javax.inject.Singleton;

@Module
public abstract class AuthorizationModule {
    @Binds
    @Singleton
    public abstract AuthorizationService authorizationService(AuthorizationServiceImpl service);

    @Binds
    @Singleton
    public abstract PolicyService policyService(PolicyServiceImpl service);
}
