package jc121f1.model.authz;

/**
 * A concrete resource, represented in policies as mc:service:accountId:type/id.
 * Fields are case-sensitive and must not contain wildcard or delimiter characters.
 * The evaluator validates syntax and compatibility with the registered action.
 *
 * <p>Account targets use resourceType "account" and resourceId equal to accountId.
 * Construct references from trusted ownership metadata; this value alone is not
 * proof of ownership. Policy patterns are separate from concrete references.
 */
public record ResourceReference(String service, String accountId, String resourceType, String resourceId) {
}
