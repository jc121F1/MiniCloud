package jc121f1.model.authz;

import lombok.Builder;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;

import java.util.List;
import java.util.Objects;

/**
 * Policy content. PolicyService validates version, size limits, registered actions,
 * resource compatibility, pattern syntax, and account scope before persistence.
 * V1 supports version 1 and the matching rules in docs/authz-design.md.
 */
@Builder
@DynamoDbImmutable(builder = PolicyDocument.PolicyDocumentBuilder.class)
public record PolicyDocument(int version, List<Statement> statements) {
    public PolicyDocument {
        statements = List.copyOf(statements);
    }

    /** Action and resource lists are alternatives; both must match the request. */
    @Builder
    @DynamoDbImmutable(builder = Statement.StatementBuilder.class)
    public record Statement(Effect effect, List<String> actions, List<String> resources) {
        public Statement {
            Objects.requireNonNull(effect, "effect");
            actions = List.copyOf(actions);
            resources = List.copyOf(resources);
        }
    }

    public enum Effect {
        ALLOW,
        DENY
    }
}
