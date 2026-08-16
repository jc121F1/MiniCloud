package jc121f1.wbs.handlers.delete;

import io.javalin.http.Context;
import jc121f1.services.instance.InstanceService;
import jc121f1.wbs.handlers.InstanceHandler;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;

public class DeleteInstanceHandler extends InstanceHandler {

    @Inject
    public DeleteInstanceHandler(InstanceService instanceService) {
        super(instanceService);
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {

    }
}
