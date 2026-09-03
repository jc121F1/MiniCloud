package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import jc121f1.model.instance.api.request.ListInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.util.List;

public class ListInstanceHandler extends InstanceHandler {

    @Inject
    public ListInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @Override
    public void handle(@NotNull Context ctx) {
        ListInstanceRequest request = new ListInstanceRequest();

        List<Instance> instances = instanceService.list(request);

        ctx.json(instances);
    }
}
