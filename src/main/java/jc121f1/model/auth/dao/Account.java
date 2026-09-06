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

@Builder(toBuilder = true)
@JsonDeserialize(builder = Account.AccountBuilder.class)
@DynamoDbImmutable(builder = Account.AccountBuilder.class)
public record Account(
        @DynamoDbPartitionKey @OpenApiRequired String accountId,         // e.g. "acct_7f3a9c" — public identifier
        @OpenApiRequired String name,               // display name, e.g. "Acme Corp"
        @OpenApiRequired AccountStatus status,      // ACTIVE, SUSPENDED, CLOSED
        @OpenApiRequired String ownerId,         // userId of the original creator/billing owner
        @OpenApiRequired
        @JsonSerialize(using = TruncatedInstantSerializer.class)
        @JsonDeserialize(using = TruncatedInstantDeserializer.class)
        Instant createdAt,
        @OpenApiRequired
        @JsonSerialize(using = TruncatedInstantSerializer.class)
        @JsonDeserialize(using = TruncatedInstantDeserializer.class)
        Instant updatedAt
    ) {

    @JsonPOJOBuilder(withPrefix = "")
    public static class AccountBuilder {

        @DynamoDbPartitionKey
        public Account.AccountBuilder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        @DynamoDbSecondaryPartitionKey(indexNames = "AccountOwnerIndex")
        @OpenApiIgnore
        public Account.AccountBuilder accountOwnerId(String accountOwnerId) {
            this.accountId = accountOwnerId;
            return this;
        }
    }

    public enum AccountStatus {
        ACTIVE,
        SUSPENDED,
        CLOSED
    }

    @DynamoDbIgnore
    @OpenApiIgnore
    public Account copyOf() {
        return this.toBuilder().build();
    }
}
