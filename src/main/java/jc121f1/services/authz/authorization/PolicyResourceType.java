package jc121f1.services.authz.authorization;

import jc121f1.model.authz.ResourceType;

public enum PolicyResourceType implements ResourceType {
    ACCOUNT("account"),
    POLICY("policy"),
    USER("user"),
    CREDENTIAL("credential");

    private final String value;

    PolicyResourceType(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
