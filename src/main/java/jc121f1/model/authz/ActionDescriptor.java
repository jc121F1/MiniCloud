package jc121f1.model.authz;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable wire catalog entry. No service classes, enum names, privilege flags,
 * or executable code cross the service boundary. ActionRegistry validates entries.
 */
public record ActionDescriptor(String service, String operation, Set<String> resourceTypes) {
    public ActionDescriptor {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(operation, "operation");
        resourceTypes = Set.copyOf(resourceTypes);
    }

    public static ActionDescriptor from(ActionDefinition definition) {
        return new ActionDescriptor(definition.service().value(), definition.operation(),
                definition.resourceTypes().stream().map(ResourceType::value).collect(Collectors.toUnmodifiableSet()));
    }

    public String value() {
        return service + ":" + operation;
    }

    public boolean supports(ResourceReference resource) {
        return resource != null && service.equals(resource.service()) && resourceTypes.contains(resource.resourceType());
    }
}
