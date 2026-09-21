package jc121f1.services.auth.authorization;

import jc121f1.model.authz.ResourceType;

public enum AuthResourceType implements ResourceType {
    ACCOUNT("account"),
    USER("user"),
    CREDENTIAL("credential");

    private final String value;

    AuthResourceType(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
