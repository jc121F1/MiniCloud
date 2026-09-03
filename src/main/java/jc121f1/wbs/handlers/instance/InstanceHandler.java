package jc121f1.wbs.handlers.instance;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.services.instance.InstanceService;
import jc121f1.wbs.handlers.BaseHandler;

public abstract class InstanceHandler implements BaseHandler {
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "InstanceService is an injected service dependency and is intentionally shared."
    )
    protected final InstanceService instanceService;

    public InstanceHandler(InstanceService instanceService) {
        this.instanceService = instanceService;
    }
}
