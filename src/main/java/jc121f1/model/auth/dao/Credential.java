package jc121f1.model.auth.dao;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.javalin.openapi.OpenApiIgnore;
import io.javalin.openapi.OpenApiRequired;
import jc121f1.model.TruncatedInstantDeserializer;
import jc121f1.model.TruncatedInstantSerializer;
import lombok.Builder;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.time.Instant;
import java.util.Set;

@Builder(toBuilder = true)
@JsonDeserialize(builder = Credential.CredentialBuilder.class)
@DynamoDbImmutable(builder = Credential.CredentialBuilder.class)
public record Credential(
        @DynamoDbPartitionKey @OpenApiRequired String credentialId,
        // e.g. "svc_abc123" — public identifier
        @OpenApiIgnore String secretHash,
        // hashed secret, never store or serialize plaintext — hidden from API responses
        @OpenApiRequired String accountId,
        @OpenApiRequired Set<String> scopes,
        // e.g. {"compute:read", "compute:write"}
        @OpenApiRequired boolean revoked,
        @OpenApiRequired
        @JsonSerialize(using = TruncatedInstantSerializer.class)
        @JsonDeserialize(using = TruncatedInstantDeserializer.class)
        Instant createdAt,
        @JsonSerialize(using = TruncatedInstantSerializer.class)
        @JsonDeserialize(using = TruncatedInstantDeserializer.class)
        Instant lastUsedAt
) {

    @JsonPOJOBuilder(withPrefix = "")
    public static class CredentialBuilder {

        @DynamoDbPartitionKey
        public Credential.CredentialBuilder credentialId(String credentialId) {
            this.credentialId = credentialId;
            return this;
        }

        @DynamoDbSecondaryPartitionKey(indexNames = "CredentialAccountIndex")
        @OpenApiIgnore
        public Credential.CredentialBuilder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }
    }

    @DynamoDbIgnore
    @OpenApiIgnore
    public Credential copyOf() {
        return this.toBuilder().build();
    }

    @DynamoDbIgnore
    @OpenApiIgnore
    public boolean isUsable() {
        return !revoked;
    }
}