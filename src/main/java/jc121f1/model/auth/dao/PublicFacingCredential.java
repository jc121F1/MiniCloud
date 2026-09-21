package jc121f1.model.auth.dao;

import io.javalin.openapi.OpenApiRequired;
import lombok.Builder;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@Builder
public record PublicFacingCredential(
        @DynamoDbPartitionKey @OpenApiRequired String credentialId,
        @OpenApiRequired String accountId,
        @OpenApiRequired String secret
) {

}
