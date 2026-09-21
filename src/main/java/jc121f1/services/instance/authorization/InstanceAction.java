package jc121f1.services.instance.authorization;

import jc121f1.model.authz.ActionDefinition;
import jc121f1.model.authz.ServiceId;

import java.util.Set;

public enum InstanceAction implements ActionDefinition {
    CREATE("Create", InstanceResourceType.ACCOUNT),
    LIST("List", InstanceResourceType.ACCOUNT),
    DESCRIBE("Describe", InstanceResourceType.INSTANCE),
    START("Start", InstanceResourceType.INSTANCE),
    STOP("Stop", InstanceResourceType.INSTANCE),
    DELETE("Delete", InstanceResourceType.INSTANCE);

    private final String operation;
    private final Set<InstanceResourceType> resourceTypes;

    InstanceAction(String operation, InstanceResourceType resourceType) {
        this.operation = operation;
        this.resourceTypes = Set.of(resourceType);
    }

    @Override
    public ServiceId service() {
        return ServiceId.INSTANCE;
    }

    @Override
    public String operation() {
        return operation;
    }

    @Override
    public Set<InstanceResourceType> resourceTypes() {
        return resourceTypes;
    }
}
