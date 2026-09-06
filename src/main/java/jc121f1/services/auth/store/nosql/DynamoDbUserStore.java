package jc121f1.services.auth.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.common.store.nosql.DynamoDbStoreDefinition;
import jc121f1.common.store.nosql.UniqueConstraint;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.store.UserStore;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
public final class DynamoDbUserStore extends DynamoDbStore<User> implements UserStore {

    private static final String TABLE_NAME = "MiniCloudIdentityStore";
    private static final String USER_EMAIL_INDEX = "UserEmailIndex";

    @Inject
    public DynamoDbUserStore(final DynamoDbAsyncClient dynamoDbClient) {
        super(dynamoDbClient, createDefinition());
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbUserStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<User> table
    ) {
        super(dynamoDbAsyncClient, table, createDefinition());
    }

    private static DynamoDbStoreDefinition<User> createDefinition() {
        return new DynamoDbStoreDefinition<>(
                TABLE_NAME,
                TableSchema.fromImmutableClass(User.class),
                User::userId,
                List.of(new UniqueConstraint<>("email", User::email)),
                List.of()
        );
    }

    @Override
    public CompletableFuture<User> findByEmail(String email) {
        return this.queryByIndex(USER_EMAIL_INDEX, email).thenApply(List::getFirst);
    }
}