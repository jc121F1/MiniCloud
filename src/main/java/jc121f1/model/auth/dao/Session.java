package jc121f1.model.auth.dao;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.javalin.openapi.OpenApiIgnore;
import io.javalin.openapi.OpenApiRequired;
import jc121f1.model.TruncatedInstantDeserializer;
import jc121f1.model.TruncatedInstantSerializer;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;

@Builder(toBuilder = true)
@JsonDeserialize(builder = Session.SessionBuilder.class)
@DynamoDbImmutable(builder = Session.SessionBuilder.class)
public record Session(
        @DynamoDbPartitionKey @OpenApiRequired String token,
        @OpenApiRequired String accountId,
        @OpenApiIgnore String subjectId,
        @OpenApiRequired SubjectType subjectType,
        @JsonIgnore
        @OpenApiRequired
        @EqualsAndHashCode.Exclude
        @JsonSerialize(using = TruncatedInstantSerializer.class)
        @JsonDeserialize(using = TruncatedInstantDeserializer.class)
        Instant expiresAt


) {
    public enum SubjectType {
        USER,
        CREDENTIAL
    }
}
