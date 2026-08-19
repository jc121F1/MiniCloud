package jc121f1.dagger;

import dagger.Component;

import javax.inject.Singleton;

@Singleton
@Component(modules = {ServiceModule.class, EnvironmentModule.class})
public interface WebserviceComponent extends WebserviceHandlers {
}
