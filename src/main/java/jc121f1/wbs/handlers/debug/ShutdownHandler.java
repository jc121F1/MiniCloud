package jc121f1.wbs.handlers.debug;

import io.javalin.http.Context;
import jc121f1.wbs.handlers.BaseHandler;
import org.jetbrains.annotations.NotNull;

public class ShutdownHandler implements BaseHandler {

    public ShutdownHandler() {
    }
    @Override
    public void handle(@NotNull Context ctx) {
        //WebService.stop();
        ctx.json("OK");
    }
}
