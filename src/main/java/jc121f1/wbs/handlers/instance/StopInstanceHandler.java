package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
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

    @OpenApi(
            summary = "Stop specified instance",
            operationId = "stopInstance",
            path = "/instances/stop",
            methods = HttpMethod.POST,
            tags = {"Instance"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = StopInstanceRequest.class)}),
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = Instance.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        StopInstanceRequest request = ctx.bodyAsClass(StopInstanceRequest.class);

        Instance instance = instanceService.stop(request);

        ctx.json(instance);
    }
}
