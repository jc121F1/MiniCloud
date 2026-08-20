package jc121f1.wbs.handlers.debug;

import io.javalin.http.Context;
import jc121f1.wbs.WebService;
import jc121f1.wbs.handlers.BaseHandler;
import org.jetbrains.annotations.NotNull;

public class ShutdownHandler implements BaseHandler {

    public ShutdownHandler() {
    }
    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        WebService.stop();
        ctx.json("OK");
    }
}
