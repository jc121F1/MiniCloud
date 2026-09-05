package jc121f1.wbs.handlers.instance;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import jc121f1.model.instance.api.request.GetInstanceRequest;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.InstanceService;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.util.List;


public class GetInstanceHandler extends InstanceHandler {
    @Inject
    public GetInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @OpenApi(
            summary = "Describe specified instance",
            operationId = "describeInstance",
            path = "/instances/describe",
            methods = HttpMethod.POST,
            tags = {"Instance"},
            requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = GetInstanceRequest.class)}),
            responses = {
                    @OpenApiResponse(status = "200", content = {@OpenApiContent(from = List.class)})
            }
    )
    @Override
    public void handle(@NotNull Context ctx) {
        GetInstanceRequest request = ctx.bodyAsClass(GetInstanceRequest.class);

        Instance instance = instanceService.get(request);

        ctx.json(instance);
    }
}
