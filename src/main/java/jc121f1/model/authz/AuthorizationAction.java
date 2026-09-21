package jc121f1.model.authz;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** Closed V1 action registry. Policy wildcards never bypass these action rules. */
public enum AuthorizationAction {
    CREATE_INSTANCE("instance:Create", false, true, "account"),
    LIST_INSTANCES("instance:List", false, true, "account"),
    DESCRIBE_INSTANCE("instance:Describe", false, true, "instance"),
    START_INSTANCE("instance:Start", false, true, "instance"),
    STOP_INSTANCE("instance:Stop", false, true, "instance"),
    DELETE_INSTANCE("instance:Delete", false, true, "instance"),
    CREATE_USER("auth:CreateUser", false, true, "account"),
    DESCRIBE_USER("auth:DescribeUser", false, true, "user"),
    DELETE_USER("auth:DeleteUser", false, true, "user"),
    GENERATE_CREDENTIAL("auth:GenerateCredential", false, false, "account"),
    INVALIDATE_CREDENTIAL("auth:InvalidateCredential", false, true, "credential"),
    TRANSFER_OWNERSHIP("auth:TransferOwnership", true, false, "account"),
    CREATE_POLICY("authz:CreatePolicy", true, false, "account"),
    GET_POLICY("authz:GetPolicy", true, false, "policy"),
    LIST_POLICIES("authz:ListPolicies", true, false, "account"),
    UPDATE_POLICY("authz:UpdatePolicy", true, false, "policy"),
    DELETE_POLICY("authz:DeletePolicy", true, false, "policy"),
    ATTACH_POLICY("authz:AttachPolicy", true, false, "policy"),
    DETACH_POLICY("authz:DetachPolicy", true, false, "policy"),
    LIST_ATTACHED_POLICIES("authz:ListAttachedPolicies", true, false, "user", "credential");

    private final String value;
    private final boolean ownerOnly;
    private final boolean credentialAllowed;
    private final Set<String> resourceTypes;

    AuthorizationAction(String value, boolean ownerOnly, boolean credentialAllowed, String... resourceTypes) {
        this.value = value;
        this.ownerOnly = ownerOnly;
        this.credentialAllowed = credentialAllowed;
        this.resourceTypes = Set.of(resourceTypes);
    }

    public String value() {
        return value;
    }

    public String service() {
        return value.substring(0, value.indexOf(':'));
    }

    public boolean ownerOnly() {
        return ownerOnly;
    }

    public boolean credentialAllowed() {
        return credentialAllowed;
    }

    public boolean supports(ResourceReference resource) {
        return resource != null && service().equals(resource.service())
                && resourceTypes.contains(resource.resourceType());
    }

    /** Exact case-sensitive lookup; patterns and unknown actions return empty. */
    public static Optional<AuthorizationAction> find(String value) {
        return Arrays.stream(values()).filter(action -> action.value.equals(value)).findFirst();
    }
}
