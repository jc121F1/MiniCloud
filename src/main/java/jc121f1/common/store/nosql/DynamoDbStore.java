package jc121f1.common.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import jc121f1.common.store.GenericStore;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.EnhancedGlobalSecondaryIndex;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public abstract class DynamoDbStore<T> implements GenericStore<T> {

    private static final String UNIQUE_LOCK_PREFIX =
            "__unique_lock__";

    private static final String RECORD_TYPE_ATTRIBUTE =
            "__record_type";

    private static final String ITEM_RECORD_TYPE =
            "ITEM";

    private static final String UNIQUE_LOCK_RECORD_TYPE =
            "UNIQUE_LOCK";

    private final DynamoDbAsyncClient dynamoDbAsyncClient;

    private final DynamoDbAsyncTable<T> table;

    private final DynamoDbStoreDefinition<T> definition;

    private final String partitionKeyName;

    @VisibleForTesting
    protected DynamoDbStore(
            DynamoDbAsyncClient dynamoDbAsyncClient,
            DynamoDbAsyncTable<T> table,
            DynamoDbStoreDefinition<T> definition
    ) {
        this.dynamoDbAsyncClient =
                Objects.requireNonNull(
                        dynamoDbAsyncClient,
                        "dynamoDbAsyncClient must not be null"
                );

        this.table =
                Objects.requireNonNull(
                        table,
                        "table must not be null"
                );

        this.definition =
                Objects.requireNonNull(
                        definition,
                        "definition must not be null"
                );

        this.partitionKeyName =
                definition.tableSchema()
                        .tableMetadata()
                        .primaryPartitionKey();
    }

    protected DynamoDbStore(
            DynamoDbAsyncClient dynamoDbAsyncClient,
            DynamoDbStoreDefinition<T> definition
    ) {
        this(
                dynamoDbAsyncClient,
                createTable(
                        dynamoDbAsyncClient,
                        definition
                ),
                definition
        );
    }

    private static <T> DynamoDbAsyncTable<T> createTable(
            DynamoDbAsyncClient dynamoDbAsyncClient,
            DynamoDbStoreDefinition<T> definition
    ) {
        DynamoDbEnhancedAsyncClient enhancedClient =
                DynamoDbEnhancedAsyncClient.builder()
                        .dynamoDbClient(dynamoDbAsyncClient)
                        .build();

        return enhancedClient.table(
                definition.tableName(),
                definition.tableSchema()
        );
    }

    @Override
    public CompletableFuture<Optional<T>> get(String id) {
        Objects.requireNonNull(
                id,
                "id must not be null"
        );

        /*
         * Unique-lock records live in the same physical table.
         * Never expose them through the public store API.
         */
        if (isUniqueLockId(id)) {
            return CompletableFuture.completedFuture(
                    Optional.empty()
            );
        }

        return table.getItem(request ->
                request.key(
                        Key.builder()
                                .partitionValue(id)
                                .build()
                )
        ).thenApply(Optional::ofNullable);
    }

    @Override
    public CompletableFuture<List<T>> list() {
        List<T> items = new CopyOnWriteArrayList<>();

        return table.scan(request ->
                        request
                                .filterExpression(
                                        Expression.builder()
                                                .expression("#recordType = :itemType")
                                                .expressionNames(
                                                        Map.of("#recordType", RECORD_TYPE_ATTRIBUTE))
                                                .expressionValues(
                                                        Map.of(":itemType", stringAttribute(ITEM_RECORD_TYPE)))
                                                .build()
                                )
                )
                .items()
                .subscribe(items::add)
                .thenApply(_ -> List.copyOf(items));
    }

    @Override
    public CompletableFuture<T> create(T item) {
        Objects.requireNonNull(
                item,
                "item must not be null"
        );

        Map<String, AttributeValue> itemMap =
                new HashMap<>(
                        definition.tableSchema()
                                .itemToMap(item, true)
                );

        /*
         * Mark this as an actual user item so scans and queries
         * can distinguish it from unique-lock records.
         */
        itemMap.put(
                RECORD_TYPE_ATTRIBUTE,
                stringAttribute(ITEM_RECORD_TYPE)
        );

        List<TransactWriteItem> transactItems =
                new ArrayList<>();

        /*
         * Create the actual item.
         */
        transactItems.add(
                TransactWriteItem.builder()
                        .put(
                                Put.builder()
                                        .tableName(
                                                definition.tableName()
                                        )
                                        .item(itemMap)
                                        .conditionExpression(
                                                "attribute_not_exists(#id)"
                                        )
                                        .expressionAttributeNames(
                                                Map.of(
                                                        "#id",
                                                        partitionKeyName
                                                )
                                        )
                                        .build()
                        )
                        .build()
        );

        /*
         * Create one lock for each unique constraint.
         */
        for (UniqueConstraint<T> constraint
                : definition.uniqueConstraints()) {

            transactItems.add(
                    createUniqueLockItem(
                            constraint,
                            constraint.extractKey(item)
                    )
            );
        }

        return dynamoDbAsyncClient
                .transactWriteItems(
                        TransactWriteItemsRequest.builder()
                                .transactItems(transactItems)
                                .build()
                )
                .thenApply(_ -> item);
    }

    @Override
    public CompletableFuture<T> update(
            T previous,
            T updated
    ) {
        Objects.requireNonNull(
                previous,
                "previous must not be null"
        );

        Objects.requireNonNull(
                updated,
                "updated must not be null"
        );

        String previousId =
                definition.extractId(previous);

        String updatedId =
                definition.extractId(updated);

        if (!previousId.equals(updatedId)) {
            throw new IllegalArgumentException(
                    "Item id cannot be changed during update"
            );
        }

        Map<String, AttributeValue> updatedItemMap =
                new HashMap<>(
                        definition.tableSchema()
                                .itemToMap(updated, true)
                );

        /*
         * Preserve the item discriminator.
         */
        updatedItemMap.put(
                RECORD_TYPE_ATTRIBUTE,
                stringAttribute(ITEM_RECORD_TYPE)
        );

        List<TransactWriteItem> transactItems =
                new ArrayList<>();

        /*
         * Update unique locks when a unique value changes.
         */
        for (UniqueConstraint<T> constraint
                : definition.uniqueConstraints()) {

            String previousValue =
                    constraint.extractKey(previous);

            String updatedValue =
                    constraint.extractKey(updated);

            if (previousValue.equals(updatedValue)) {
                continue;
            }

            /*
             * Release the old unique value.
             */
            transactItems.add(
                    deleteUniqueLockItem(
                            constraint,
                            previousValue
                    )
            );

            /*
             * Claim the new unique value.
             *
             * The conditional put guarantees that two
             * concurrent updates cannot claim the same value.
             */
            transactItems.add(
                    createUniqueLockItem(
                            constraint,
                            updatedValue
                    )
            );
        }

        /*
         * Update the actual item.
         */
        transactItems.add(
                TransactWriteItem.builder()
                        .put(
                                Put.builder()
                                        .tableName(
                                                definition.tableName()
                                        )
                                        .item(updatedItemMap)
                                        .conditionExpression(
                                                "attribute_exists(#id)"
                                        )
                                        .expressionAttributeNames(
                                                Map.of(
                                                        "#id",
                                                        partitionKeyName
                                                )
                                        )
                                        .build()
                        )
                        .build()
        );

        return dynamoDbAsyncClient
                .transactWriteItems(
                        TransactWriteItemsRequest.builder()
                                .transactItems(transactItems)
                                .build()
                )
                .thenApply(_ -> updated);
    }

    @Override
    public CompletableFuture<Void> delete(T item) {
        Objects.requireNonNull(
                item,
                "item must not be null"
        );

        String id =
                definition.extractId(item);

        List<TransactWriteItem> transactItems =
                new ArrayList<>();

        /*
         * Delete the actual item.
         */
        transactItems.add(
                TransactWriteItem.builder()
                        .delete(
                                Delete.builder()
                                        .tableName(
                                                definition.tableName()
                                        )
                                        .key(
                                                Map.of(
                                                        partitionKeyName,
                                                        stringAttribute(id)
                                                )
                                        )
                                        .build()
                        )
                        .build()
        );

        /*
         * Release all unique values.
         */
        for (UniqueConstraint<T> constraint
                : definition.uniqueConstraints()) {

            transactItems.add(
                    deleteUniqueLockItem(
                            constraint,
                            constraint.extractKey(item)
                    )
            );
        }

        return dynamoDbAsyncClient
                .transactWriteItems(
                        TransactWriteItemsRequest.builder()
                                .transactItems(transactItems)
                                .build()
                )
                .thenApply(_ -> null);
    }

    /**
     * Queries a global secondary index while ensuring that
     * unique-lock records are never returned.
     */
    protected CompletableFuture<List<T>> queryByIndex(
            String indexName,
            String partitionValue
    ) {
        Objects.requireNonNull(
                indexName,
                "indexName must not be null"
        );

        Objects.requireNonNull(
                partitionValue,
                "partitionValue must not be null"
        );

        List<T> results =
                new CopyOnWriteArrayList<>();

        return table.index(indexName)
                .query(request ->
                        request
                                .queryConditional(
                                        QueryConditional.keyEqualTo(
                                                Key.builder()
                                                        .partitionValue(
                                                                partitionValue
                                                        )
                                                        .build()
                                        )
                                )
                                .filterExpression(Expression.builder()
                                        .expression("#recordType = :itemType")
                                        .expressionNames(Map.of("#recordType", RECORD_TYPE_ATTRIBUTE))
                                        .expressionValues(Map.of(":itemType", stringAttribute(ITEM_RECORD_TYPE)))
                                        .build())
                )
                .flatMapIterable(Page::items)
                .subscribe(results::add)
                .thenApply(_ -> List.copyOf(results));
    }

    private TransactWriteItem createUniqueLockItem(
            UniqueConstraint<T> constraint,
            String value
    ) {
        String lockId =
                uniqueLockId(
                        constraint.name(),
                        value
                );

        return TransactWriteItem.builder()
                .put(
                        Put.builder()
                                .tableName(
                                        definition.tableName()
                                )
                                .item(
                                        Map.of(
                                                partitionKeyName,
                                                stringAttribute(lockId),

                                                RECORD_TYPE_ATTRIBUTE,
                                                stringAttribute(
                                                        UNIQUE_LOCK_RECORD_TYPE
                                                )
                                        )
                                )
                                .conditionExpression(
                                        "attribute_not_exists(#id)"
                                )
                                .expressionAttributeNames(
                                        Map.of(
                                                "#id",
                                                partitionKeyName
                                        )
                                )
                                .build()
                )
                .build();
    }

    private TransactWriteItem deleteUniqueLockItem(
            UniqueConstraint<T> constraint,
            String value
    ) {
        String lockId =
                uniqueLockId(
                        constraint.name(),
                        value
                );

        return TransactWriteItem.builder()
                .delete(
                        Delete.builder()
                                .tableName(
                                        definition.tableName()
                                )
                                .key(
                                        Map.of(
                                                partitionKeyName,
                                                stringAttribute(lockId)
                                        )
                                )
                                .build()
                )
                .build();
    }

    private String uniqueLockId(
            String constraintName,
            String value
    ) {
        return "%s#%s#%s".formatted(
                UNIQUE_LOCK_PREFIX,
                constraintName,
                value
        );
    }

    private boolean isUniqueLockId(String id) {
        return id.startsWith(
                UNIQUE_LOCK_PREFIX + "#"
        );
    }

    private AttributeValue stringAttribute(String value) {
        return AttributeValue.builder()
                .s(value)
                .build();
    }

    protected CompletableFuture<Void> initialize() {
        return dynamoDbAsyncClient
                .describeTable(request ->
                        request.tableName(
                                definition.tableName()
                        )
                )
                .handle((response, error) -> {
                    if (error == null) {
                        log.info(
                                "Table {} already exists.",
                                definition.tableName()
                        );

                        return CompletableFuture
                                .<Void>completedFuture(null);
                    }

                    Throwable cause =
                            unwrap(error);

                    if (!(cause
                            instanceof ResourceNotFoundException)) {

                        return CompletableFuture
                                .<Void>failedFuture(cause);
                    }

                    log.info(
                            "Table {} not found. Creating it.",
                            definition.tableName()
                    );

                    List<EnhancedGlobalSecondaryIndex> indices =
                            definition.globalSecondaryIndices()
                                    .stream()
                                    .map(gsi -> EnhancedGlobalSecondaryIndex.builder()
                                            .indexName(gsi.indexName())
                                            .projection(Projection.builder()
                                                    .projectionType(gsi.projectionType())
                                                    .build())
                                            .build())
                                    .toList();

                    return table.createTable(request ->
                                    request.globalSecondaryIndices(indices))
                            .thenCompose(_ ->
                                    dynamoDbAsyncClient
                                            .waiter()
                                            .waitUntilTableExists(
                                                    request ->
                                                            request.tableName(
                                                                    definition.tableName()
                                                            )
                                            )
                            )
                            .handle((_, createError) -> {

                                if (createError == null) {
                                    log.info(
                                            "Table {} created.",
                                            definition.tableName()
                                    );

                                    return null;
                                }

                                Throwable createCause =
                                        unwrap(createError);

                                if (createCause
                                        instanceof ResourceInUseException) {

                                    log.info(
                                            "Table {} was created concurrently.",
                                            definition.tableName()
                                    );

                                    return (Void) null;
                                }

                                throw new CompletionException(
                                        createCause
                                );
                            });
                })
                .thenCompose(future -> future);
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;

        while (
                cause instanceof CompletionException
                        && cause.getCause() != null
        ) {
            cause = cause.getCause();
        }

        return cause;
    }

    @Override
    protected final void finalize() {
    }
}