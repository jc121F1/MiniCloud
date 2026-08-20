package jc121f1.wbs;

import io.javalin.Javalin;
import jc121f1.dagger.DaggerWebserviceComponent;
import jc121f1.dagger.WebserviceComponent;
import jc121f1.dagger.WebserviceHandlers;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.wbs.handlers.debug.ShutdownHandler;
import jc121f1.wbs.handlers.delete.DeleteInstanceHandler;
import jc121f1.wbs.handlers.get.GetInstanceHandler;
import jc121f1.wbs.handlers.get.ListInstanceHandler;
import jc121f1.wbs.handlers.get.RootHandler;
import jc121f1.wbs.handlers.post.CreateInstanceHandler;
import jc121f1.wbs.handlers.post.StartInstanceHandler;
import jc121f1.wbs.handlers.post.StopInstanceHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

@Slf4j
public class WebService {

    private static Javalin app;
    private static ComputeBackend backend;

    public static Javalin create(WebserviceHandlers component) {
        RootHandler rootHandler = component.rootHandler();
        GetInstanceHandler getInstanceHandler = component.getInstanceHandler();
        ListInstanceHandler listInstanceHandler = component.listInstanceHandler();
        CreateInstanceHandler createInstanceHandler = component.createInstanceHandler();
        DeleteInstanceHandler deleteInstanceHandler = component.deleteInstanceHandler();
        StopInstanceHandler stopInstanceHandler = component.stopInstanceHandler();
        StartInstanceHandler startInstanceHandler = component.startInstanceHandler();

        backend = component.computeBackend();
        boolean debug = Optional.ofNullable(System.getenv("DEBUG_APP")).orElse(System.getProperty("DEBUG_APP")) != null;
        return Javalin.create(config -> {
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
                if (debug) {
                    get("shutdown", new ShutdownHandler());
                }
            });
        });
    }

    public static void start() {
        WebserviceComponent component =
                DaggerWebserviceComponent.create();

        app = create(component).start(7070);
    }

    public static void stop() throws Exception {
        backend.close();
        app.stop();
    }
}
