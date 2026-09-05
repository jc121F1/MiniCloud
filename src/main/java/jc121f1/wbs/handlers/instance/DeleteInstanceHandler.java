package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
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

    @OpenApi(
            summary = "Delete specified instance",
            operationId = "deleteInstance",
            path = "/instances/delete",
            methods = HttpMethod.POST,
            tags = {"Instance"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = DeleteInstanceRequest.class)}),
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = Instance.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        DeleteInstanceRequest request = ctx.bodyAsClass(DeleteInstanceRequest.class);

        Instance instance = instanceService.delete(request);

        ctx.json(instance);
    }
}
