package jc121f1.wbs.handlers.post;

import io.javalin.http.Context;
import jc121f1.model.instance.api.StartInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
import jc121f1.wbs.handlers.InstanceHandler;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class StartInstanceHandler extends InstanceHandler {

    @Inject
    public StartInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        StartInstanceRequest request = ctx.bodyAsClass(StartInstanceRequest.class);

        Instance instance = instanceService.start(request);

        ctx.json(instance);
    }
}
