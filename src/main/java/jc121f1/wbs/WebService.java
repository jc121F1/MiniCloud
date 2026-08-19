package jc121f1.wbs;

import io.javalin.Javalin;
import jc121f1.dagger.DaggerWebserviceComponent;
import jc121f1.dagger.WebserviceComponent;
import jc121f1.dagger.WebserviceHandlers;
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

    public static Javalin create(WebserviceHandlers component) {
        RootHandler rootHandler = component.rootHandler();
        GetInstanceHandler getInstanceHandler = component.getInstanceHandler();
        ListInstanceHandler listInstanceHandler = component.listInstanceHandler();
        CreateInstanceHandler createInstanceHandler = component.createInstanceHandler();
        DeleteInstanceHandler deleteInstanceHandler = component.deleteInstanceHandler();
        StopInstanceHandler stopInstanceHandler = component.stopInstanceHandler();
        StartInstanceHandler startInstanceHandler = component.startInstanceHandler();

        return Javalin.create(config -> {
            config.routes.apiBuilder(() -> {
                get(rootHandler);

                path("instances", () -> {
                    get(listInstanceHandler);
                    post(createInstanceHandler);
                    delete("delete", deleteInstanceHandler);
                    post("stop", stopInstanceHandler);
                    post("start", startInstanceHandler);
                    post("describe", getInstanceHandler);
                });
            });
        });
    }

    public static void start() {
        WebserviceComponent component =
                DaggerWebserviceComponent.create();

        create(component).start(7070);
    }
}
