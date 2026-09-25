package jc121f1.services.instance.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.common.store.nosql.DynamoDbStoreDefinition;
import jc121f1.common.store.nosql.GlobalSecondaryIndexDefinition;
import jc121f1.common.store.nosql.UniqueConstraint;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.store.InstanceStore;
import jc121f1.services.instance.exceptions.ConflictException;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
public final class DynamoDbInstanceStore extends DynamoDbStore<Instance> implements InstanceStore {

    public static final String INSTANCE_GSI = "InstanceNameIndex";
    private static final String TABLE_NAME = "MiniCloudInstanceStore";

    @Inject
    public DynamoDbInstanceStore(final DynamoDbAsyncClient dynamoDbClient) {
        super(dynamoDbClient, createDefinition(TABLE_NAME));
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbInstanceStore(final DynamoDbAsyncClient dynamoDbClient, String tableName) {
        super(dynamoDbClient, createDefinition(tableName));
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbInstanceStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<Instance> table
    ) {
        super(dynamoDbAsyncClient, table, createDefinition(TABLE_NAME));
    }

    private static DynamoDbStoreDefinition<Instance> createDefinition(String tableName) {
        return new DynamoDbStoreDefinition<>(
                tableName,
                TableSchema.fromImmutableClass(Instance.class),
                Instance::id,
                List.of(new UniqueConstraint<>("name", Instance::name)),
                List.of(new GlobalSecondaryIndexDefinition(INSTANCE_GSI, ProjectionType.ALL))
        );
    }

    @Override
    public CompletableFuture<Instance> update(Instance previous, Instance updated, Expression condition) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(updated, "updated");
        if (!Objects.equals(previous.accountId(), updated.accountId())) {
            throw new IllegalArgumentException("Instance ownership cannot change");
        }
        long revision = previous.revision() == null ? 1 : Math.incrementExact(previous.revision());
        Instance versioned = updated.toBuilder().revision(revision).build();
        return super.update(previous, versioned, combine(expectedRevision(previous), condition))
                .exceptionally(error -> {
                    throw mutationFailure(error);
                });
    }

    @Override
    public CompletableFuture<Void> delete(Instance instance, Expression condition) {
        return super.delete(instance, combine(expectedRevision(instance), condition))
                .exceptionally(error -> {
                    throw mutationFailure(error);
                });
    }

    private Expression expectedRevision(Instance instance) {
        Expression.Builder expression = Expression.builder()
                .expressionNames(Map.of("#revision", "revision", "#instanceId", "id"));
        if (instance.revision() == null) {
            return expression.expression("attribute_exists(#instanceId) AND attribute_not_exists(#revision)").build();
        }
        return expression.expression("attribute_exists(#instanceId) AND #revision = :revision")
                .expressionValues(Map.of(":revision", AttributeValue.fromN(instance.revision().toString())))
                .build();
    }

    private RuntimeException mutationFailure(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TransactionCanceledException canceled && canceled.cancellationReasons().stream()
                .anyMatch(reason -> "ConditionalCheckFailed".equals(reason.code()))) {
            return new ConflictException("Instance changed concurrently or name is already in use");
        }
        return cause instanceof RuntimeException runtime ? runtime : new CompletionException(cause);
    }

    @Override
    public CompletableFuture<Optional<Instance>> getByName(String name) {
        // Leverage the new generic query method
        return queryByIndex(INSTANCE_GSI, name)
                .thenApply(instances -> {
                    if (instances.size() > 1) {
                        throw new IllegalStateException("More than one instance returned from get by name!");
                    }
                    return instances.stream().findFirst();
                });
    }
}
