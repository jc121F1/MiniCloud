package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import jc121f1.model.instance.api.request.CreateInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class CreateInstanceHandler extends InstanceHandler {

    @Inject
    public CreateInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @Override
    public void handle(@NotNull Context ctx) {
        CreateInstanceRequest request = ctx.bodyAsClass(CreateInstanceRequest.class);

        Instance instance = instanceService.create(request);

        ctx.json(instance);
    }
}
