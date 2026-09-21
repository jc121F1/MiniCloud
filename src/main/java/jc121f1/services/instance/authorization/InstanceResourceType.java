package jc121f1.services.instance.authorization;

import jc121f1.model.authz.ResourceType;

public enum InstanceResourceType implements ResourceType {
    ACCOUNT("account"),
    INSTANCE("instance");

    private final String value;

    InstanceResourceType(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
