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

public class DynamoDbSessionStore extends DynamoDbStore<Session> implements SessionStore {
    private static final String TABLE_NAME = "MiniCloudSessionStore";

    @Inject
    public DynamoDbSessionStore(final DynamoDbAsyncClient dynamoDbClient) {
        super(dynamoDbClient, createDefinition());
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbSessionStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<Session> table
    ) {
        super(dynamoDbAsyncClient, table, createDefinition());
        enableTtl();
    }

    private static DynamoDbStoreDefinition<Session> createDefinition() {
        return new DynamoDbStoreDefinition<>(
                TABLE_NAME,
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
