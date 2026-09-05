package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
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

    @OpenApi(
            summary = "List all instances",
            operationId = "listInstances",
            path = "/instances",
            methods = HttpMethod.GET,
            tags = {"Instance"},
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = List.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        ListInstanceRequest request = new ListInstanceRequest();

        List<Instance> instances = instanceService.list(request);

        ctx.json(instances);
    }
}
