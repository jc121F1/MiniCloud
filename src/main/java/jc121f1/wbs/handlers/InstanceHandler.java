package jc121f1.wbs.handlers;

import jc121f1.services.instance.InstanceService;

import javax.inject.Inject;

public abstract class InstanceHandler implements BaseHandler {
    @Inject protected InstanceService instanceService;

    public InstanceHandler(InstanceService instanceService) {
        this.instanceService = instanceService;
    }
}
