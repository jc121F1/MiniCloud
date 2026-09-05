package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
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

    @OpenApi(
            summary = "Create instance",
            operationId = "createInstance",
            path = "/instances/create",
            methods = HttpMethod.POST,
            tags = {"Instance"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = CreateInstanceRequest.class)}),
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = Instance.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        CreateInstanceRequest request = ctx.bodyAsClass(CreateInstanceRequest.class);

        Instance instance = instanceService.create(request);

        ctx.json(instance);
    }
}
