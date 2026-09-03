package jc121f1.integration.testdagger;

import dagger.Component;
import jc121f1.dagger.EnvironmentModule;
import jc121f1.dagger.instance.InstanceWebServiceComponent;

import javax.inject.Singleton;

@Singleton
@Component(modules = {TestServiceModule.class, EnvironmentModule.class})
public interface TestInstanceWebServiceComponent extends InstanceWebServiceComponent {

}
