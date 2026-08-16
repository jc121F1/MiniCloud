package jc121f1.wbs.handlers.get;

import io.javalin.http.Context;
import jc121f1.model.instance.api.GetInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
import jc121f1.wbs.handlers.InstanceHandler;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;


public class GetInstanceHandler extends InstanceHandler {
    @Inject
    public GetInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @Override
    public void handle(@NotNull Context ctx) {
        GetInstanceRequest request = ctx.bodyAsClass(GetInstanceRequest.class);

        Instance instance = instanceService.get(request);

        ctx.json(instance);
    }
}
