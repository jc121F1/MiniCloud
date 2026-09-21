package jc121f1.service.authz;

import jc121f1.model.authz.AuthorizationAction;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.ResourceReference;
import jc121f1.services.authz.PolicyValidator;
import jc121f1.services.authz.exceptions.PolicyValidationException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class PolicyValidatorTest {
    private final PolicyValidator validator = new PolicyValidator();

    @Test
    void accepts_exact_actions_service_wildcards_and_explicit_denies() {
        validator.validate("a-1", new PolicyDocument(1, List.of(
                statement(List.of("instance:Start", "instance:Stop"), List.of("mc:instance:a-1:instance/i-1")),
                new PolicyDocument.Statement(PolicyDocument.Effect.DENY,
                        List.of("instance:*"), List.of("mc:instance:a-1:instance/*")))));
        validator.validate("a-1", policy("instance:Create", "mc:instance:a-1:account/a-1"));
        validator.validate("a-1", policy("authz:AttachPolicy", "mc:authz:a-1:policy/p-1"));
    }

    @Test
    void rejects_unknown_actions_case_changes_and_partial_wildcards() {
        for (String action : List.of("instance:start", "instance:Launch", "Instance:Start",
                "instance:St*", "*:Start", "*", "unknown:*", " instance:Start")) {
            Assertions.assertThatThrownBy(() -> validator.validate("a-1",
                    policy(action, "mc:instance:a-1:instance/*")))
                    .as(action).isInstanceOf(PolicyValidationException.class);
        }
        Assertions.assertThat(AuthorizationAction.find("instance:Start"))
                .contains(AuthorizationAction.START_INSTANCE);
        Assertions.assertThat(AuthorizationAction.find("instance:*")).isEmpty();
    }

    @Test
    void rejects_cross_account_and_malformed_resource_patterns() {
        for (String resource : List.of("mc:instance:a-2:instance/i-1", "mc:instance:*:instance/i-1",
                "mc:instance:a-1:instance/i-*", "mc:instance:a-1:instance/i/1",
                "mc:instance:a-1:instance/", "mc:instance:a-1:instance/i.1",
                "mc:instance:a-1:instance/i%2F1", "mc:instance:a-1:instance/i-1\n")) {
            Assertions.assertThatThrownBy(() -> validator.validate("a-1", policy("instance:Start", resource)))
                    .as(resource).isInstanceOf(PolicyValidationException.class);
        }
    }

    @Test
    void rejects_incompatible_actions_resources_and_mismatched_account_targets() {
        for (PolicyDocument document : List.of(
                policy("instance:Create", "mc:instance:a-1:instance/i-1"),
                policy("instance:Start", "mc:instance:a-1:account/a-1"),
                policy("instance:Create", "mc:instance:a-1:account/a-2"),
                policy("instance:Start", "mc:auth:a-1:instance/i-1"),
                new PolicyDocument(1, List.of(statement(List.of("instance:Start", "auth:DeleteUser"),
                        List.of("mc:instance:a-1:instance/i-1")))),
                new PolicyDocument(1, List.of(statement(List.of("instance:Start"),
                        List.of("mc:instance:a-1:instance/i-1", "mc:auth:a-1:user/u-1")))))) {
            Assertions.assertThatThrownBy(() -> validator.validate("a-1", document))
                    .isInstanceOf(PolicyValidationException.class);
        }
    }

    @Test
    void concrete_targets_cannot_use_wildcards_or_forge_account_resource_ids() {
        Assertions.assertThat(validator.isValidTarget(AuthorizationAction.START_INSTANCE,
                new ResourceReference("instance", "a-1", "instance", "i-1"))).isTrue();
        for (ResourceReference resource : List.of(
                new ResourceReference("instance", "*", "instance", "i-1"),
                new ResourceReference("instance", "a-1", "instance", "*"),
                new ResourceReference("instance", "a-1", "instance", "i/1"),
                new ResourceReference("auth", "a-1", "instance", "i-1"))) {
            Assertions.assertThat(validator.isValidTarget(AuthorizationAction.START_INSTANCE, resource)).isFalse();
        }
        Assertions.assertThat(validator.isValidTarget(AuthorizationAction.CREATE_INSTANCE,
                new ResourceReference("instance", "a-1", "account", "a-2"))).isFalse();
        Assertions.assertThat(validator.isValidTarget(null, null)).isFalse();
    }

    @Test
    void rejects_unsupported_versions_empty_lists_and_oversized_lists() {
        PolicyDocument.Statement valid = statement(List.of("instance:Start"), List.of("mc:instance:a-1:instance/i-1"));
        List<PolicyDocument> invalidDocuments = List.of(
                new PolicyDocument(2, List.of(valid)), new PolicyDocument(1, List.of()),
                new PolicyDocument(1, Collections.nCopies(33, valid)),
                new PolicyDocument(1, List.of(statement(List.of(), valid.resources()))),
                new PolicyDocument(1, List.of(statement(valid.actions(), List.of()))),
                new PolicyDocument(1, List.of(statement(Collections.nCopies(33, "instance:Start"), valid.resources()))),
                new PolicyDocument(1, List.of(statement(valid.actions(),
                        Collections.nCopies(33, valid.resources().getFirst())))));
        for (PolicyDocument document : invalidDocuments) {
            Assertions.assertThatThrownBy(() -> validator.validate("a-1", document))
                    .isInstanceOf(PolicyValidationException.class);
        }
        validator.validate("a-1", new PolicyDocument(1, Collections.nCopies(32, valid)));
        validator.validate("a-1", new PolicyDocument(1, List.of(statement(
                Collections.nCopies(32, "instance:Start"), Collections.nCopies(32, valid.resources().getFirst())))));
    }

    @Test
    void bounds_component_length_and_total_pattern_bytes() {
        String maximum = "mc:instance:a-1:instance/" + "i".repeat(128);
        validator.validate("a-1", policy("instance:Start", maximum));
        Assertions.assertThatThrownBy(() -> validator.validate("a-1", policy("instance:Start", maximum + "i")))
                .isInstanceOf(PolicyValidationException.class);
        PolicyDocument.Statement large = statement(List.of("instance:Start"), Collections.nCopies(32, maximum));
        validator.validate("a-1", new PolicyDocument(1, Collections.nCopies(3, large)));
        Assertions.assertThatThrownBy(() -> validator.validate("a-1", new PolicyDocument(1, Collections.nCopies(4, large))))
                .isInstanceOf(PolicyValidationException.class).hasMessageContaining("16 KiB");
    }

    @Test
    void policy_content_is_defensively_copied() {
        List<String> actions = new ArrayList<>(List.of("instance:Start"));
        List<String> resources = new ArrayList<>(List.of("mc:instance:a-1:instance/i-1"));
        List<PolicyDocument.Statement> statements = new ArrayList<>(List.of(statement(actions, resources)));
        PolicyDocument document = new PolicyDocument(1, statements);
        actions.clear();
        resources.clear();
        statements.clear();
        validator.validate("a-1", document);
        Assertions.assertThatThrownBy(() -> document.statements().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void registry_protects_management_and_credential_creation() {
        for (AuthorizationAction action : AuthorizationAction.values()) {
            if (action.service().equals("authz") || action == AuthorizationAction.TRANSFER_OWNERSHIP) {
                Assertions.assertThat(action.ownerOnly()).isTrue();
                Assertions.assertThat(action.credentialAllowed()).isFalse();
            }
        }
        Assertions.assertThat(AuthorizationAction.GENERATE_CREDENTIAL.credentialAllowed()).isFalse();
        Assertions.assertThat(AuthorizationAction.START_INSTANCE.credentialAllowed()).isTrue();
    }

    private static PolicyDocument policy(String action, String resource) {
        return new PolicyDocument(1, List.of(statement(List.of(action), List.of(resource))));
    }

    private static PolicyDocument.Statement statement(List<String> actions, List<String> resources) {
        return new PolicyDocument.Statement(PolicyDocument.Effect.ALLOW, actions, resources);
    }
}
