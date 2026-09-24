package jc121f1.dagger;

import dagger.Binds;
import dagger.Module;
import jc121f1.services.authz.AuthorizationService;
import jc121f1.services.authz.PolicyService;
import jc121f1.services.authz.audit.AuditedAuthorizationService;
import jc121f1.services.authz.audit.AuditedPolicyService;
import jc121f1.services.authz.audit.AuthorizationAuditSink;
import jc121f1.services.authz.audit.LoggingAuthorizationAuditSink;

import javax.inject.Singleton;

@Module
public abstract class AuthorizationModule {
    @Binds
    @Singleton
    public abstract AuthorizationService authorizationService(AuditedAuthorizationService service);

    @Binds
    @Singleton
    public abstract PolicyService policyService(AuditedPolicyService service);

    @Binds
    @Singleton
    public abstract AuthorizationAuditSink authorizationAuditSink(LoggingAuthorizationAuditSink sink);
}
