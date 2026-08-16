package jc121f1.wbs.handlers.get;

import io.javalin.http.Context;
import jc121f1.wbs.handlers.BaseHandler;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class RootHandler implements BaseHandler {
    private static final String HOW_TO_GUIDE = "How to use my service:";

    @Inject
    public RootHandler() {
    }

    @Override
    public void handle(@NotNull Context ctx) {
        ctx.result(HOW_TO_GUIDE);
    }
}
