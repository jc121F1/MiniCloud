package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import jc121f1.model.instance.api.request.StopInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class StopInstanceHandler extends InstanceHandler {

    @Inject
    public StopInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        StopInstanceRequest request = ctx.bodyAsClass(StopInstanceRequest.class);

        Instance instance = instanceService.stop(request);

        ctx.json(instance);
    }
}
