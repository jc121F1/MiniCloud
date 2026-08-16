package jc121f1.wbs.handlers;

import jc121f1.services.instance.InstanceService;

public abstract class InstanceHandler implements BaseHandler {

    protected final InstanceService instanceService;

    public InstanceHandler(InstanceService instanceService) {
        this.instanceService = instanceService;
    }
}
