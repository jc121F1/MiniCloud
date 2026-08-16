package jc121f1.dagger;

import dagger.Component;
import jc121f1.wbs.handlers.delete.DeleteInstanceHandler;
import jc121f1.wbs.handlers.get.GetInstanceHandler;
import jc121f1.wbs.handlers.get.ListInstanceHandler;
import jc121f1.wbs.handlers.get.RootHandler;
import jc121f1.wbs.handlers.post.CreateInstanceHandler;
import jc121f1.wbs.handlers.post.StartInstanceHandler;
import jc121f1.wbs.handlers.post.StopInstanceHandler;

import javax.inject.Singleton;

@Singleton
@Component(modules = {ServiceModule.class, EnvironmentModule.class})
public interface WebserviceComponent {
    RootHandler rootHandler();

    GetInstanceHandler getInstanceHandler();

    ListInstanceHandler listInstanceHandler();

    CreateInstanceHandler createInstanceHandler();

    DeleteInstanceHandler deleteInstanceHandler();

    StopInstanceHandler stopInstanceHandler();

    StartInstanceHandler startInstanceHandler();
}
