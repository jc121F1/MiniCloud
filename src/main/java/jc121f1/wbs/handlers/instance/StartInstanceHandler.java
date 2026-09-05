package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.instance.api.request.StartInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class StartInstanceHandler extends InstanceHandler {

    @Inject
    public StartInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @OpenApi(
            summary = "Start specified instance",
            operationId = "startInstance",
            path = "/instances/start",
            methods = HttpMethod.POST,
            tags = {"Instance"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = StartInstanceRequest.class)}),
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = Instance.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        StartInstanceRequest request = ctx.bodyAsClass(StartInstanceRequest.class);

        Instance instance = instanceService.start(request);

        ctx.json(instance);
    }
}
