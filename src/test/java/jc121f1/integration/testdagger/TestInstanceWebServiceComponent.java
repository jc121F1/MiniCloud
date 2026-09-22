package jc121f1.integration.testdagger;

import dagger.Component;
import jc121f1.dagger.EnvironmentModule;
import jc121f1.dagger.instance.InstanceWebServiceComponent;
import jc121f1.services.auth.AuthService;
import jc121f1.services.authz.AuthorizationService;

import javax.inject.Singleton;

@Singleton
@Component(modules = {TestServiceModule.class, EnvironmentModule.class})
public interface TestInstanceWebServiceComponent extends InstanceWebServiceComponent {
    AuthService authService();
    AuthorizationService authorizationService();
}
