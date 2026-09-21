package jc121f1.model.authz.dao;

import jc121f1.model.authz.PolicyDocument;
import lombok.Builder;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/** Table row: policy metadata/document or a principal's attachment reference. */
@Builder(toBuilder = true)
@DynamoDbImmutable(builder = PolicyRecord.PolicyRecordBuilder.class)
public record PolicyRecord(@DynamoDbPartitionKey String pk, @DynamoDbSortKey String sk,
                           String accountId, String policyId, Long revision, Long attachmentCount,
                           Boolean deleted, PolicyDocument document) {
}
