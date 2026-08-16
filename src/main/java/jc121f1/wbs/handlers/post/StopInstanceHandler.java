package jc121f1.wbs.handlers.post;

import io.javalin.http.Context;
import jc121f1.services.instance.InstanceService;
import jc121f1.wbs.handlers.BaseHandler;
import jc121f1.wbs.handlers.InstanceHandler;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class StopInstanceHandler extends InstanceHandler {

    @Inject
    public StopInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {

    }
}
