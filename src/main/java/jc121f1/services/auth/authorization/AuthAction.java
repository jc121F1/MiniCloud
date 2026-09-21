package jc121f1.services.auth.authorization;

import jc121f1.model.authz.ActionDefinition;
import jc121f1.model.authz.ServiceId;

import java.util.Set;

public enum AuthAction implements ActionDefinition {
    CREATE_USER("CreateUser", AuthResourceType.ACCOUNT),
    DESCRIBE_USER("DescribeUser", AuthResourceType.USER),
    DELETE_USER("DeleteUser", AuthResourceType.USER),
    GENERATE_CREDENTIAL("GenerateCredential", AuthResourceType.ACCOUNT),
    INVALIDATE_CREDENTIAL("InvalidateCredential", AuthResourceType.CREDENTIAL),
    TRANSFER_OWNERSHIP("TransferOwnership", AuthResourceType.ACCOUNT);

    private final String operation;
    private final Set<AuthResourceType> resourceTypes;

    AuthAction(String operation, AuthResourceType resourceType) {
        this.operation = operation;
        this.resourceTypes = Set.of(resourceType);
    }

    @Override
    public ServiceId service() {
        return ServiceId.AUTH;
    }

    @Override
    public String operation() {
        return operation;
    }

    @Override
    public Set<AuthResourceType> resourceTypes() {
        return resourceTypes;
    }
}
