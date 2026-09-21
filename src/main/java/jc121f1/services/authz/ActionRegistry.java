package jc121f1.services.authz;

import jc121f1.model.authz.ActionDescriptor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Immutable trusted deployment catalog. Invalid or duplicate entries fail startup. */
public final class ActionRegistry {
    public static final int MAX_ACTION_LENGTH = 128;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,127}");
    private static final Pattern OPERATION = Pattern.compile("[A-Z][A-Za-z0-9]{0,126}");
    private final Map<String, ActionDescriptor> actions;

    public ActionRegistry(Collection<ActionDescriptor> definitions) {
        Map<String, ActionDescriptor> registered = new LinkedHashMap<>();
        for (ActionDescriptor definition : definitions) {
            if (!NAME.matcher(definition.service()).matches()
                    || !OPERATION.matcher(definition.operation()).matches()
                    || definition.value().length() > MAX_ACTION_LENGTH
                    || definition.resourceTypes().isEmpty()
                    || definition.resourceTypes().stream().anyMatch(type -> !NAME.matcher(type).matches())) {
                throw new IllegalArgumentException("Invalid action definition: " + definition.value());
            }
            if (registered.putIfAbsent(definition.value(), definition) != null) {
                throw new IllegalArgumentException("Duplicate action definition: " + definition.value());
            }
        }
        if (registered.isEmpty()) {
            throw new IllegalArgumentException("Action catalog must not be empty");
        }
        actions = Map.copyOf(registered);
    }

    public Optional<ActionDescriptor> find(String value) {
        return value == null ? Optional.empty() : Optional.ofNullable(actions.get(value));
    }

    /** Exact action or entire service wildcard; unknown/partial patterns match nothing. */
    public List<ActionDescriptor> expand(String pattern) {
        return actions.values().stream()
                .filter(action -> action.value().equals(pattern) || (action.service() + ":*").equals(pattern))
                .sorted(java.util.Comparator.comparing(ActionDescriptor::value)).toList();
    }
}
