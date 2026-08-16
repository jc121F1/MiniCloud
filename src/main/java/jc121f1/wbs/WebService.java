package jc121f1.wbs;

import io.javalin.Javalin;
import jc121f1.dagger.DaggerWebserviceComponent;
import jc121f1.dagger.WebserviceComponent;
import jc121f1.wbs.handlers.delete.DeleteInstanceHandler;
import jc121f1.wbs.handlers.get.GetInstanceHandler;
import jc121f1.wbs.handlers.get.ListInstanceHandler;
import jc121f1.wbs.handlers.get.RootHandler;
import jc121f1.wbs.handlers.post.CreateInstanceHandler;
import jc121f1.wbs.handlers.post.StartInstanceHandler;
import jc121f1.wbs.handlers.post.StopInstanceHandler;

import static io.javalin.apibuilder.ApiBuilder.delete;
import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static io.javalin.apibuilder.ApiBuilder.post;

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

        app = Javalin.create(config -> {
            config.routes.apiBuilder(() -> {
                get(rootHandler);
                path("instances", () -> {
                    get(listInstanceHandler);
                    post(createInstanceHandler);
                    path("{instanceId}", () -> {
                        get(getInstanceHandler);
                        delete(deleteInstanceHandler);
                        path("stop", () -> {
                            post(stopInstanceHandler);
                        });
                        path("start", () -> {
                            post(startInstanceHandler);
                        });
                    });
                });
            });
        });
        app.start(7070);
    }
}
