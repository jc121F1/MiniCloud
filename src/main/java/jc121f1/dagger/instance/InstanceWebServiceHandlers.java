package jc121f1.dagger.instance;

import jc121f1.wbs.handlers.instance.DeleteInstanceHandler;
import jc121f1.wbs.handlers.instance.GetInstanceHandler;
import jc121f1.wbs.handlers.instance.ListInstanceHandler;
import jc121f1.wbs.handlers.RootHandler;
import jc121f1.wbs.handlers.instance.CreateInstanceHandler;
import jc121f1.wbs.handlers.instance.StartInstanceHandler;
import jc121f1.wbs.handlers.instance.StopInstanceHandler;

public interface InstanceWebServiceHandlers {
    RootHandler rootHandler();

    GetInstanceHandler getInstanceHandler();

    ListInstanceHandler listInstanceHandler();

    CreateInstanceHandler createInstanceHandler();

    DeleteInstanceHandler deleteInstanceHandler();

    StopInstanceHandler stopInstanceHandler();

    StartInstanceHandler startInstanceHandler();

}
