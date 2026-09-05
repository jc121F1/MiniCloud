package jc121f1.common.store.nosql;

import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import java.util.Objects;

public record GlobalSecondaryIndexDefinition(
        String indexName,
        ProjectionType projectionType
) {
    public GlobalSecondaryIndexDefinition {
        Objects.requireNonNull(indexName, "indexName must not be null");
        Objects.requireNonNull(projectionType, "projectionType must not be null");
    }
}