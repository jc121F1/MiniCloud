package jc121f1.services.instance.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.common.store.nosql.DynamoDbStoreDefinition;
import jc121f1.common.store.nosql.GlobalSecondaryIndexDefinition;
import jc121f1.common.store.nosql.UniqueConstraint;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.store.InstanceStore;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
public final class DynamoDbInstanceStore extends DynamoDbStore<Instance> implements InstanceStore {

    public static final String INSTANCE_GSI = "InstanceNameIndex";
    private static final String TABLE_NAME = "MiniCloudInstanceStore";

    @Inject
    public DynamoDbInstanceStore(final DynamoDbAsyncClient dynamoDbClient) {
        super(dynamoDbClient, createDefinition());
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbInstanceStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<Instance> table
    ) {
        super(dynamoDbAsyncClient, table, createDefinition());
    }

    private static DynamoDbStoreDefinition<Instance> createDefinition() {
        return new DynamoDbStoreDefinition<>(
                TABLE_NAME,
                TableSchema.fromImmutableClass(Instance.class),
                Instance::id,
                List.of(new UniqueConstraint<>("name", Instance::name)),
                List.of(new GlobalSecondaryIndexDefinition(INSTANCE_GSI, ProjectionType.ALL))
        );
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