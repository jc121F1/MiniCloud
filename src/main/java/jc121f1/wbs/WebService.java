package jc121f1.wbs;

import io.javalin.Javalin;
import jc121f1.dagger.DaggerWebserviceComponent;
import jc121f1.dagger.WebserviceComponent;
import jc121f1.services.instance.compute.ComputeBackend;
import jc121f1.wbs.handlers.delete.DeleteInstanceHandler;
import jc121f1.wbs.handlers.get.GetInstanceHandler;
import jc121f1.wbs.handlers.get.ListInstanceHandler;
import jc121f1.wbs.handlers.get.RootHandler;
import jc121f1.wbs.handlers.post.CreateInstanceHandler;
import jc121f1.wbs.handlers.post.StartInstanceHandler;
import jc121f1.wbs.handlers.post.StopInstanceHandler;
import lombok.extern.slf4j.Slf4j;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

@Slf4j
public class WebService {
    private static Javalin app;

    public static void start() {
        WebserviceComponent wbsComponent = DaggerWebserviceComponent.create();
        RootHandler rootHandler = wbsComponent.rootHandler();

        GetInstanceHandler getInstanceHandler = wbsComponent.getInstanceHandler();
        ListInstanceHandler listInstanceHandler = wbsComponent.listInstanceHandler();
        CreateInstanceHandler createInstanceHandler = wbsComponent.createInstanceHandler();
        DeleteInstanceHandler deleteInstanceHandler = wbsComponent.deleteInstanceHandler();
        StopInstanceHandler stopInstanceHandler = wbsComponent.stopInstanceHandler();
        StartInstanceHandler startInstanceHandler = wbsComponent.startInstanceHandler();
        ComputeBackend computeBackend = wbsComponent.computeBackend();

        app = Javalin.create(config -> {
            config.routes.apiBuilder(() -> {
                get(rootHandler);
                path("instances", () -> {
                    get(listInstanceHandler);
                    post(createInstanceHandler);
                    delete("delete", deleteInstanceHandler);
                    post("stop", stopInstanceHandler);
                        post("start", startInstanceHandler);
                    path("describe", () -> {
                        post(getInstanceHandler);
                    });
                });
            });
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.warn("Shutdown hook running");

            try {
                computeBackend.close();
                log.warn("Compute backend closed");
            } catch (Exception e) {
                log.error("Failed to close compute backend", e);
            }

            try {
                app.stop();
                log.warn("Webservice stopped");
            } catch (Exception e) {
                log.error("Failed to stop webservice", e);
            }
        }));
        app.start(7070);
    }
}
