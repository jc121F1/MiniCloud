package jc121f1.services.authz.authorization;

import jc121f1.model.authz.ActionDefinition;
import jc121f1.model.authz.ServiceId;

import java.util.Set;

public enum PolicyAction implements ActionDefinition {
    CREATE_POLICY("CreatePolicy", PolicyResourceType.ACCOUNT),
    GET_POLICY("GetPolicy", PolicyResourceType.POLICY),
    LIST_POLICIES("ListPolicies", PolicyResourceType.ACCOUNT),
    UPDATE_POLICY("UpdatePolicy", PolicyResourceType.POLICY),
    DELETE_POLICY("DeletePolicy", PolicyResourceType.POLICY),
    ATTACH_POLICY("AttachPolicy", PolicyResourceType.POLICY),
    DETACH_POLICY("DetachPolicy", PolicyResourceType.POLICY),
    LIST_ATTACHED_POLICIES("ListAttachedPolicies", PolicyResourceType.USER, PolicyResourceType.CREDENTIAL);

    private final String operation;
    private final Set<PolicyResourceType> resourceTypes;

    PolicyAction(String operation, PolicyResourceType... resourceTypes) {
        this.operation = operation;
        this.resourceTypes = Set.of(resourceTypes);
    }

    @Override
    public ServiceId service() {
        return ServiceId.AUTHZ;
    }

    @Override
    public String operation() {
        return operation;
    }

    @Override
    public Set<PolicyResourceType> resourceTypes() {
        return resourceTypes;
    }
}
