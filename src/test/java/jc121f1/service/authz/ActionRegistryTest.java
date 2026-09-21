package jc121f1.service.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import jc121f1.dagger.AuthorizationCatalogModule;
import jc121f1.model.authz.ActionDescriptor;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.ResourceReference;
import jc121f1.model.authz.ServiceId;
import jc121f1.services.authz.ActionRegistry;
import jc121f1.services.authz.PolicyValidator;
import jc121f1.services.instance.authorization.InstanceAction;
import jc121f1.services.instance.authorization.InstanceResourceType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class ActionRegistryTest {
    @Test
    void service_owned_enums_produce_stable_wire_identifiers() {
        Assertions.assertThat(InstanceAction.START.value()).isEqualTo("instance:Start");
        ActionRegistry registry = AuthorizationCatalogModule.actionRegistry();
        Assertions.assertThat(registry.expand("instance:*")).hasSize(6);
        Assertions.assertThat(registry.find(InstanceAction.START.value()))
                .contains(new ActionDescriptor("instance", "Start", Set.of("instance")));
        Assertions.assertThat(ActionDescriptor.from(InstanceAction.START).supports(
                ResourceReference.of(ServiceId.INSTANCE, "a-1", InstanceResourceType.INSTANCE, "i-1"))).isTrue();
    }

    @Test
    void wire_catalog_supports_a_service_without_adding_it_to_the_java_enum() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = """
                {"service":"storage","operation":"Read","resourceTypes":["bucket"]}
                """;
        ActionDescriptor external = mapper.readValue(json, ActionDescriptor.class);
        Assertions.assertThat(mapper.readValue(mapper.writeValueAsString(external), ActionDescriptor.class))
                .isEqualTo(external);
        ActionRegistry registry = new ActionRegistry(List.of(external));
        PolicyValidator validator = new PolicyValidator(registry);
        validator.validate("a-1", new PolicyDocument(1, List.of(new PolicyDocument.Statement(
                PolicyDocument.Effect.ALLOW, List.of("storage:Read"), List.of("mc:storage:a-1:bucket/b-1")))));
        Assertions.assertThat(validator.isValidTarget(external,
                new ResourceReference("storage", "a-1", "bucket", "b-1"))).isTrue();
        Assertions.assertThat(registry.find("instance:Start")).isEmpty();
    }

    @Test
    void rejects_duplicate_identifiers_even_when_the_metadata_differs() {
        ActionDescriptor start = ActionDescriptor.from(InstanceAction.START);
        for (ActionDescriptor duplicate : List.of(start, new ActionDescriptor("instance", "Start", Set.of("account")))) {
            Assertions.assertThatThrownBy(() -> new ActionRegistry(List.of(start, duplicate)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        }
    }

    @Test
    void rejects_malformed_or_empty_catalogs() {
        for (ActionDescriptor definition : List.of(
                new ActionDescriptor("Instance", "Start", Set.of("instance")),
                new ActionDescriptor("instance:*", "Start", Set.of("instance")),
                new ActionDescriptor("instance", "start", Set.of("instance")),
                new ActionDescriptor("instance", "Start:*", Set.of("instance")),
                new ActionDescriptor("instance", "Start", Set.of()),
                new ActionDescriptor("instance", "Start", Set.of("Instance")),
                new ActionDescriptor("instance", "Start", Set.of("instance/*")),
                new ActionDescriptor("instance", "A".repeat(128), Set.of("instance")))) {
            Assertions.assertThatThrownBy(() -> new ActionRegistry(List.of(definition)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        Assertions.assertThatThrownBy(() -> new ActionRegistry(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wildcards_are_exact_service_scoped_and_case_sensitive() {
        ActionRegistry registry = AuthorizationCatalogModule.actionRegistry();
        for (String pattern : List.of("*", "*:Start", "instance:St*", "Instance:*", "instance:start", "storage:*")) {
            Assertions.assertThat(registry.expand(pattern)).as(pattern).isEmpty();
        }
        Assertions.assertThat(registry.expand("instance:Start"))
                .containsExactly(ActionDescriptor.from(InstanceAction.START));
    }

    @Test
    void callers_cannot_inject_or_replace_registered_resource_compatibility() {
        ActionRegistry registry = AuthorizationCatalogModule.actionRegistry();
        PolicyValidator validator = new PolicyValidator(registry);
        Assertions.assertThat(validator.isValidTarget(new ActionDescriptor("instance", "Start", Set.of("account")),
                new ResourceReference("instance", "a-1", "account", "a-1"))).isFalse();
        Assertions.assertThat(validator.isValidTarget(new ActionDescriptor("storage", "Read", Set.of("bucket")),
                new ResourceReference("storage", "a-1", "bucket", "b-1"))).isFalse();
    }

    @Test
    void catalogs_are_immutable_snapshots_of_their_inputs() {
        Set<String> types = new HashSet<>(Set.of("instance"));
        ActionDescriptor descriptor = new ActionDescriptor("instance", "Start", types);
        List<ActionDescriptor> definitions = new ArrayList<>(List.of(descriptor));
        ActionRegistry registry = new ActionRegistry(definitions);
        types.clear();
        definitions.clear();
        Assertions.assertThat(registry.find("instance:Start")).contains(ActionDescriptor.from(InstanceAction.START));
        Assertions.assertThatThrownBy(() -> descriptor.resourceTypes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
