package jc121f1.services.instance.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.store.InstanceStore;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

@Slf4j
public class DynamoDbInstanceStore implements InstanceStore {

    @VisibleForTesting public static final String INSTANCE_GSI = "InstanceNameIndex";

    private static final String TABLE_NAME = "MiniCloudInstanceStore";

    private final DynamoDbAsyncClient dynamoDbAsyncClient;

    private final DynamoDbAsyncTable<Instance> table;

    @SuppressFBWarnings(
            value = "EI2",
            justification = "AWS SDK clients are immutable service abstractions and are intentionally injected"
    )
    @VisibleForTesting public DynamoDbInstanceStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<Instance> table
    ) {
        this.dynamoDbAsyncClient = dynamoDbAsyncClient;
        this.table = table;
    }

    @Inject
    public DynamoDbInstanceStore(final DynamoDbAsyncClient dynamoDbClient) {
        this(dynamoDbClient,
                DynamoDbEnhancedAsyncClient.builder()
                        .dynamoDbClient(dynamoDbClient)
                        .build()
                        .table(
                                TABLE_NAME,
                                TableSchema.fromImmutableClass(Instance.class)
                        )
        );

        initialize().join();
    }

    @Override
    public CompletableFuture<Optional<Instance>> get(String instanceId) {
        return table.getItem(request -> request.key(
                Key.builder()
                        .partitionValue(instanceId)
                        .build()
        )).thenApply(Optional::ofNullable);
    }

    @Override
    public CompletableFuture<Optional<Instance>> getByName(String name) {
        List<Instance> instances = new ArrayList<>(2);

        return table.index(INSTANCE_GSI)
                .query(request -> request.queryConditional(
                        QueryConditional.keyEqualTo(
                                Key.builder()
                                        .partitionValue(name)
                                        .build()
                        )
                ))
                .flatMapIterable(Page::items)
                .subscribe(instance -> {
                    if (instances.size() >= 2) {
                        return;
                    }

                    instances.add(instance);
                })
                .thenApply(_ -> {
                    if (instances.size() > 1) {
                        throw new IllegalStateException(
                                "More than one instance returned from get by name!"
                        );
                    }

                    return instances.stream().findFirst();
                });
    }

    @Override
    public CompletableFuture<List<Instance>> list() {
        List<Instance> instances = new ArrayList<>();
        Consumer<Instance> consumer = instances::add;
        return table.scan()
                .items()
                .subscribe(consumer)
                .thenApply(_ -> instances);
    }

    @Override
    public CompletableFuture<Instance> create(Instance instance) {
        Map<String, AttributeValue> instanceItem =
                TableSchema.fromImmutableClass(Instance.class)
                        .itemToMap(instance, true);

        Map<String, AttributeValue> nameLockItem = Map.of(
                "id",
                AttributeValue.builder()
                        .s("__name_lock__" + instance.getName())
                        .build()
        );

        TransactWriteItemsRequest request =
                TransactWriteItemsRequest.builder()
                        .transactItems(
                                TransactWriteItem.builder()
                                        .put(Put.builder()
                                                .tableName(TABLE_NAME)
                                                .item(instanceItem)
                                                .conditionExpression(
                                                        "attribute_not_exists(id)"
                                                )
                                                .build())
                                        .build(),

                                TransactWriteItem.builder()
                                        .put(Put.builder()
                                                .tableName(TABLE_NAME)
                                                .item(nameLockItem)
                                                .conditionExpression(
                                                        "attribute_not_exists(id)"
                                                )
                                                .build())
                                        .build()
                        )
                        .build();

        return dynamoDbAsyncClient
                .transactWriteItems(request)
                .thenApply(_ -> instance);
    }

    @Override
    public CompletableFuture<Instance> update(Instance previous, Instance updated) {
        Map<String, AttributeValue> updatedInstanceItem =
                TableSchema.fromImmutableClass(Instance.class)
                        .itemToMap(updated, true);

        List<TransactWriteItem> items = new ArrayList<>();

        if (!previous.getName().equals(updated.getName())) {
            items.add(
                    TransactWriteItem.builder()
                            .delete(delete -> delete
                                    .tableName(TABLE_NAME)
                                    .key(Map.of(
                                            "id",
                                            AttributeValue.builder()
                                                    .s("__name_lock__" + previous.getName())
                                                    .build()
                                    )))
                            .build()
            );

            items.add(
                    TransactWriteItem.builder()
                            .put(Put.builder()
                                    .tableName(TABLE_NAME)
                                    .item(Map.of(
                                            "id",
                                            AttributeValue.builder()
                                                    .s("__name_lock__" + updated.getName())
                                                    .build()
                                    ))
                                    .conditionExpression("attribute_not_exists(id)")
                                    .build())
                            .build()
            );
        }

        items.add(
                TransactWriteItem.builder()
                        .put(Put.builder()
                                .tableName(TABLE_NAME)
                                .item(updatedInstanceItem)
                                .conditionExpression(
                                        "attribute_exists(id) AND #name = :previousName"
                                )
                                .expressionAttributeNames(Map.of(
                                        "#name", "name"
                                ))
                                .expressionAttributeValues(Map.of(
                                        ":previousName",
                                        AttributeValue.builder()
                                                .s(previous.getName())
                                                .build()
                                ))
                                .build())
                        .build()
        );

        return dynamoDbAsyncClient
                .transactWriteItems(
                        TransactWriteItemsRequest.builder()
                                .transactItems(items)
                                .build()
                )
                .thenApply(_ -> updated);
    }

    @Override
    public CompletableFuture<Void> delete(Instance instance) {
        TransactWriteItemsRequest request =
                TransactWriteItemsRequest.builder()
                        .transactItems(
                                TransactWriteItem.builder()
                                        .delete(delete -> delete
                                                .tableName(TABLE_NAME)
                                                .key(Map.of(
                                                        "id",
                                                        AttributeValue.builder()
                                                                .s(instance.getId())
                                                                .build()
                                                )))
                                        .build(),
                                TransactWriteItem.builder()
                                        .delete(delete -> delete
                                                .tableName(TABLE_NAME)
                                                .key(Map.of(
                                                        "id",
                                                        AttributeValue.builder()
                                                                .s("__name_lock__" + instance.getName())
                                                                .build()
                                                )))
                                        .build()
                        )
                        .build();

        return dynamoDbAsyncClient
                .transactWriteItems(request)
                .thenApply(_ -> null);
    }

    private CompletableFuture<Void> initialize() {
        return dynamoDbAsyncClient.describeTable(request ->
                request.tableName(TABLE_NAME)
        ).handle((response, error) -> {
            if (error == null) {
                log.info("Table {} already exists.", TABLE_NAME);
                return CompletableFuture.<Void>completedFuture(null);
            }

            Throwable cause = unwrap(error);

            if (!(cause instanceof ResourceNotFoundException)) {
                return CompletableFuture.<Void>failedFuture(cause);
            }

            log.info("Table {} not found. Creating it.", TABLE_NAME);

            return table.createTable(request ->
                            request.globalSecondaryIndices(index -> index
                                            .indexName(INSTANCE_GSI)
                                            .projection(
                                                    projection -> projection
                                                            .projectionType(ProjectionType.ALL)
                                            ))
                    )
                    .handle((_, createError) -> {
                        if (createError == null) {
                            log.info("Table {} created.", TABLE_NAME);
                            return (Void) null;
                        }

                        Throwable createCause = unwrap(createError);

                        if (createCause instanceof ResourceInUseException) {
                            log.info(
                                    "Table {} was created concurrently.",
                                    TABLE_NAME
                            );
                            return (Void) null;
                        }

                        throw new CompletionException(createCause);
                    });
        }).thenCompose(future -> future);
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;

        while (cause instanceof CompletionException
                && cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause;
    }
}