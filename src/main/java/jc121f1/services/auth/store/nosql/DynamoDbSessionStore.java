package jc121f1.services.auth.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.common.store.nosql.DynamoDbStoreDefinition;
import jc121f1.model.auth.dao.Session;
import jc121f1.services.auth.store.SessionStore;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class DynamoDbSessionStore extends DynamoDbStore<Session> implements SessionStore {
    private static final String TABLE_NAME = "MiniCloudSessionStore";

    @Inject
    public DynamoDbSessionStore(final DynamoDbAsyncClient dynamoDbClient) {
        super(dynamoDbClient, createDefinition(TABLE_NAME));
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbSessionStore(final DynamoDbAsyncClient dynamoDbClient, String tableName) {
        super(dynamoDbClient, createDefinition(tableName));
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbSessionStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<Session> table
    ) {
        super(dynamoDbAsyncClient, table, createDefinition(TABLE_NAME));
        enableTtl();
    }

    @Override
    public CompletableFuture<Optional<Session>> get(String id) {
        return super.get(id, true);
    }

    private static DynamoDbStoreDefinition<Session> createDefinition(String tableName) {
        return new DynamoDbStoreDefinition<>(
                tableName,
                TableSchema.fromImmutableClass(Session.class),
                Session::token,
                List.of(),
                List.of()
        );
    }

    private void enableTtl() {
        this.dynamoDbAsyncClient.updateTimeToLive(
                UpdateTimeToLiveRequest.builder()
                        .tableName(TABLE_NAME)
                        .timeToLiveSpecification(
                                TimeToLiveSpecification.builder()
                                        .attributeName("expiresAt")
                                        .enabled(true)
                                        .build())
                        .build());
    }
}
