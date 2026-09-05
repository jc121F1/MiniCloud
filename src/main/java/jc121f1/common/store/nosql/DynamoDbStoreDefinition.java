package jc121f1.common.store.nosql;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public record DynamoDbStoreDefinition<T>(
        String tableName,
        TableSchema<T> tableSchema,
        Function<T, String> idExtractor,
        List<UniqueConstraint<T>> uniqueConstraints,
        List<GlobalSecondaryIndexDefinition> globalSecondaryIndices
) {

    public DynamoDbStoreDefinition {
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(tableSchema, "tableSchema must not be null");
        Objects.requireNonNull(idExtractor, "idExtractor must not be null");
        Objects.requireNonNull(
                uniqueConstraints,
                "uniqueConstraints must not be null"
        );
        Objects.requireNonNull(globalSecondaryIndices, "globalSecondaryIndices must not be null");


        if (tableName.isBlank()) {
            throw new IllegalArgumentException(
                    "tableName must not be blank"
            );
        }


        globalSecondaryIndices = List.copyOf(globalSecondaryIndices);
        int totalItemsInTx = 1 + uniqueConstraints.size();
        if (totalItemsInTx > 100) {
            throw new IllegalArgumentException("Too many unique constraints. Transactions are limited to 100 items.");
        }

        uniqueConstraints = List.copyOf(uniqueConstraints);

    }

    public String extractId(T item) {
        Objects.requireNonNull(item, "item must not be null");

        String id = idExtractor.apply(item);

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Item id must not be null or blank"
            );
        }

        return id;
    }
}