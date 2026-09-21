package jc121f1.model.authz;

import java.util.Set;

/** Service-owned action contract. Privilege rules are owned separately by Authz. */
public interface ActionDefinition {
    ServiceId service();

    String operation();

    Set<? extends ResourceType> resourceTypes();

    default String value() {
        return service().value() + ":" + operation();
    }
}
