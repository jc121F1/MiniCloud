package jc121f1.integration.testdagger;

import dagger.Component;
import jc121f1.dagger.EnvironmentModule;
import jc121f1.dagger.WebserviceHandlers;

import javax.inject.Singleton;

@Singleton
@Component(modules = {TestServiceModule.class, EnvironmentModule.class})
public interface TestWebserviceComponent extends WebserviceHandlers {

}
