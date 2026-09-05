package jc121f1.wbs.services;

import io.javalin.Javalin;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import jc121f1.dagger.instance.InstanceWebServiceComponent;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.wbs.WebService;
import jc121f1.wbs.exceptions.MiniCloudExceptionMapper;
import jc121f1.wbs.handlers.RootHandler;
import jc121f1.wbs.handlers.instance.CreateInstanceHandler;
import jc121f1.wbs.handlers.instance.DeleteInstanceHandler;
import jc121f1.wbs.handlers.instance.GetInstanceHandler;
import jc121f1.wbs.handlers.instance.ListInstanceHandler;
import jc121f1.wbs.handlers.instance.StartInstanceHandler;
import jc121f1.wbs.handlers.instance.StopInstanceHandler;

import javax.inject.Inject;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

public class InstanceWebService extends WebService {
    private static final String HOSTNAME = "instance";
    private static final int PORT = 7070;
    private final InstanceWebServiceComponent component;

    @Inject
    public InstanceWebService(InstanceWebServiceComponent component) {
        super(component.jmDNSManager());
        this.component = component;
    }

    @Override
    protected int getPort() {
        return PORT;
    }

    @Override
    public Javalin create() {
        RootHandler rootHandler = component.rootHandler();
        GetInstanceHandler getInstanceHandler = component.getInstanceHandler();
        ListInstanceHandler listInstanceHandler = component.listInstanceHandler();
        CreateInstanceHandler createInstanceHandler = component.createInstanceHandler();
        DeleteInstanceHandler deleteInstanceHandler = component.deleteInstanceHandler();
        StopInstanceHandler stopInstanceHandler = component.stopInstanceHandler();
        StartInstanceHandler startInstanceHandler = component.startInstanceHandler();
        ComputeBackend computeBackend = component.computeBackend();
        //Boolean debug = component.debug();
        Boolean disableJmDNS = component.disableJmDNS();
        MiniCloudExceptionMapper exceptionMapper = component.exceptionMapper();
        return Javalin.create(config -> {
            config.registerPlugin(new OpenApiPlugin(pluginConfig -> {
                pluginConfig.withDefinitionConfiguration((version, definition) -> {
                    definition.info(info -> info.title("OpenAPI"));
                });
            }));
            config.registerPlugin(new SwaggerPlugin());
            config.registerPlugin(new ReDocPlugin());
            config.routes.exception(Exception.class, exceptionMapper::mapException);
            config.events.serverStarted(() -> {
                if (!disableJmDNS) {
                    this.startJmdns(HOSTNAME, PORT);
                }
            });
            config.events.serverStopping(() -> {
                if (!disableJmDNS) {
                    this.stopJmdns(HOSTNAME);
                }

                computeBackend.close();
            });
            config.routes.apiBuilder(() -> {
                get(rootHandler);

                path("instances", () -> {
                    get(listInstanceHandler);
                    post(createInstanceHandler);
                    post("delete", deleteInstanceHandler);
                    post("stop", stopInstanceHandler);
                    post("start", startInstanceHandler);
                    post("describe", getInstanceHandler);
                });
            });
        });
    }
}
