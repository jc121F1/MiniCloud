package jc121f1.model.auth.dao;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.javalin.openapi.OpenApiIgnore;
import io.javalin.openapi.OpenApiRequired;
import jc121f1.model.TruncatedInstantDeserializer;
import jc121f1.model.TruncatedInstantSerializer;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.time.Instant;

@Builder(toBuilder = true)
@JsonDeserialize(builder = User.UserBuilder.class)
@DynamoDbImmutable(builder = User.UserBuilder.class)
public record User (
    @DynamoDbPartitionKey @OpenApiRequired String userId,
    @OpenApiRequired String email,
    @OpenApiIgnore String passwordHash,
    @OpenApiRequired String accountId,
    @JsonIgnore
    @OpenApiRequired
    @EqualsAndHashCode.Exclude
    @JsonSerialize(using = TruncatedInstantSerializer.class)
    @JsonDeserialize(using = TruncatedInstantDeserializer.class)
    Instant createdAt) {

    @JsonPOJOBuilder(withPrefix = "")
    public static class UserBuilder {

        @DynamoDbPartitionKey
        public User.UserBuilder userId(String id) {
            this.userId = id;
            return this;
        }

        @DynamoDbSecondaryPartitionKey(indexNames = "UserEmailIndex")
        @OpenApiIgnore
        public User.UserBuilder email(String email) {
            this.email = email;
            return this;
        }
    }

    @DynamoDbIgnore
    @OpenApiIgnore
    public User copyOf() {
        return this.toBuilder().build();
    }
}
