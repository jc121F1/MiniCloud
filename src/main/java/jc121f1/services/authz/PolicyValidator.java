package jc121f1.services.authz;

import jc121f1.model.authz.ActionDescriptor;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.ResourceReference;
import jc121f1.services.authz.exceptions.PolicyValidationException;

import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates policy syntax and registry compatibility without accessing storage. */
public final class PolicyValidator {
    public static final int MAX_STATEMENTS = 32;
    public static final int MAX_ENTRIES = 32;
    public static final int MAX_COMPONENT_LENGTH = 128;
    public static final int MAX_PATTERN_BYTES = 16 * 1024;
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern RESOURCE = Pattern.compile(
            "mc:([a-z][a-z0-9-]{0,127}):([A-Za-z0-9_-]{1,128}):"
                    + "([a-z][a-z0-9-]{0,127})/([A-Za-z0-9_-]{1,128}|\\*)");

    private final ActionRegistry registry;

    @Inject
    public PolicyValidator(ActionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Every action pattern and resource must have at least one compatible partner.
     * A service wildcard applies only to registered actions supporting the matched
     * resource type. Empty documents never grant permissions implicitly.
     */
    public void validate(String accountId, PolicyDocument document) {
        Objects.requireNonNull(document, "document");
        require(isValidIdentifier(accountId), "Invalid account ID");
        require(document.version() == 1, "Unsupported policy version");
        require(!document.statements().isEmpty() && document.statements().size() <= MAX_STATEMENTS,
                "Policy must contain 1 to 32 statements");
        int patternBytes = 0;
        for (PolicyDocument.Statement statement : document.statements()) {
            require(!statement.actions().isEmpty() && statement.actions().size() <= MAX_ENTRIES,
                    "Statement must contain 1 to 32 actions");
            require(!statement.resources().isEmpty() && statement.resources().size() <= MAX_ENTRIES,
                    "Statement must contain 1 to 32 resources");
            List<ResourceReference> resources = statement.resources().stream()
                    .map(this::parseResourcePattern).toList();
            for (ResourceReference resource : resources) {
                require(accountId.equals(resource.accountId()), "Cross-account resource pattern");
            }
            for (String actionPattern : statement.actions()) {
                List<ActionDescriptor> actions = expandActionPattern(actionPattern);
                require(actions.stream().anyMatch(action -> resources.stream().anyMatch(action::supports)),
                        "Action has no compatible resource");
            }
            for (ResourceReference resource : resources) {
                require(statement.actions().stream().flatMap(value -> expandActionPattern(value).stream())
                        .anyMatch(action -> action.supports(resource)), "Resource has no compatible action");
            }
            for (String value : statement.actions()) {
                patternBytes += value.getBytes(StandardCharsets.UTF_8).length;
            }
            for (String value : statement.resources()) {
                patternBytes += value.getBytes(StandardCharsets.UTF_8).length;
            }
            require(patternBytes <= MAX_PATTERN_BYTES, "Policy pattern content exceeds 16 KiB");
        }
    }

    public List<ActionDescriptor> expandActionPattern(String value) {
        require(value != null && value.length() <= ActionRegistry.MAX_ACTION_LENGTH, "Invalid action pattern");
        List<ActionDescriptor> actions = registry.expand(value);
        require(!actions.isEmpty(), "Unknown action or unsupported action pattern");
        return actions;
    }

    public ResourceReference parseResourcePattern(String value) {
        require(value != null && value.length() <= 4 * MAX_COMPONENT_LENGTH + 6, "Invalid resource pattern");
        Matcher matcher = RESOURCE.matcher(value);
        require(matcher.matches(), "Invalid resource pattern");
        ResourceReference resource = new ResourceReference(
                matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
        require(!"account".equals(resource.resourceType()) || "*".equals(resource.resourceId())
                || resource.accountId().equals(resource.resourceId()), "Account resource ID must equal account ID");
        return resource;
    }

    /** Concrete evaluation targets cannot contain policy wildcards. */
    public boolean isValidTarget(ActionDescriptor action, ResourceReference resource) {
        return action != null && registry.find(action.value()).filter(action::equals).isPresent()
                && resource != null && action.supports(resource)
                && isValidIdentifier(resource.accountId()) && isValidIdentifier(resource.resourceId())
                && (!"account".equals(resource.resourceType()) || resource.accountId().equals(resource.resourceId()));
    }

    public static boolean isValidIdentifier(String value) {
        return value != null && COMPONENT.matcher(value).matches();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new PolicyValidationException(message);
        }
    }
}
