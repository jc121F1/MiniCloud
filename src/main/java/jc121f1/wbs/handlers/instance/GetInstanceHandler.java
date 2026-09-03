package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import jc121f1.model.instance.api.request.GetInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
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
