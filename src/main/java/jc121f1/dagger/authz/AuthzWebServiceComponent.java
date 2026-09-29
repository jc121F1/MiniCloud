package jc121f1.dagger.authz;

import dagger.Component;
import jc121f1.dagger.EnvironmentModule;
import jc121f1.dagger.ServiceModule;
import jc121f1.dagger.WebServiceComponent;
import jc121f1.dagger.auth.AuthHandlers;
import jc121f1.dagger.auth.AuthServiceModule;
import jc121f1.wbs.handlers.authz.PolicyHandlers;

import javax.inject.Singleton;

@Singleton
@Component(modules = {AuthServiceModule.class, ServiceModule.class, EnvironmentModule.class})
public interface AuthzWebServiceComponent extends WebServiceComponent, AuthHandlers {
    PolicyHandlers policyHandlers();
}
