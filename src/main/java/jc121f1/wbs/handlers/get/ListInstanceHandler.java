package jc121f1.wbs.handlers.get;

import io.javalin.http.Context;
import jc121f1.services.instance.InstanceService;
import jc121f1.wbs.handlers.InstanceHandler;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class ListInstanceHandler extends InstanceHandler {

    @Inject
    public ListInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {

    }
}
