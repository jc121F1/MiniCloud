package jc121f1.dagger.auth;

import dagger.Component;
import jc121f1.dagger.EnvironmentModule;
import jc121f1.dagger.ServiceModule;
import jc121f1.dagger.WebServiceComponent;

import javax.inject.Singleton;

@Singleton
@Component(modules = {AuthServiceModule.class, ServiceModule.class, EnvironmentModule.class})
public interface AuthWebServiceComponent extends WebServiceComponent, AuthWebServiceHandlers {
}
