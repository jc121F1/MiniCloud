package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import jc121f1.model.instance.api.request.DeleteInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class DeleteInstanceHandler extends InstanceHandler {

    @Inject
    public DeleteInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        DeleteInstanceRequest request = ctx.bodyAsClass(DeleteInstanceRequest.class);

        Instance instance = instanceService.delete(request);

        ctx.json(instance);
    }
}
