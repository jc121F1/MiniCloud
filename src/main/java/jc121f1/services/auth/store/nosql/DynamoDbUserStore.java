package jc121f1.services.auth.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.common.store.nosql.DynamoDbStoreDefinition;
import jc121f1.common.store.nosql.GlobalSecondaryIndexDefinition;
import jc121f1.common.store.nosql.UniqueConstraint;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.store.UserStore;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
public final class DynamoDbUserStore extends DynamoDbStore<User> implements UserStore {

    private static final String TABLE_NAME = "MiniCloudUserStore";
    private static final String USER_EMAIL_INDEX = "UserEmailIndex";

    @Inject
    public DynamoDbUserStore(final DynamoDbAsyncClient dynamoDbClient) {
        super(dynamoDbClient, createDefinition(TABLE_NAME));
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbUserStore(final DynamoDbAsyncClient dynamoDbClient, String tableName) {
        super(dynamoDbClient, createDefinition(tableName));
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbUserStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<User> table
    ) {
        super(dynamoDbAsyncClient, table, createDefinition(TABLE_NAME));
    }

    private static DynamoDbStoreDefinition<User> createDefinition(String tableName) {
        return new DynamoDbStoreDefinition<>(
                tableName,
                TableSchema.fromImmutableClass(User.class),
                User::userId,
                List.of(new UniqueConstraint<>("email", User::email)),
                List.of(new GlobalSecondaryIndexDefinition(USER_EMAIL_INDEX, ProjectionType.ALL))
        );
    }

    @Override
    public CompletableFuture<User> findByEmail(String email) {
        return this.queryByIndex(USER_EMAIL_INDEX, email).thenApply(List::getFirst);
    }

    @Override
    public CompletableFuture<Optional<User>> get(String id) {
        return super.get(id, true);
    }

    TransactWriteItem sameAccountCheck(User user) {
        return checkItem(user, Expression.builder().expression("attribute_exists(#id) AND #account = :account")
                .expressionNames(Map.of("#id", "userId", "#account", "accountId"))
                .expressionValues(Map.of(":account", AttributeValue.fromS(user.accountId()))).build());
    }

    List<TransactWriteItem> guardedDeleteItems(User user) {
        return deleteItems(user, Expression.builder()
                .expression("attribute_exists(#id) AND #account = :account AND #email = :email")
                .expressionNames(Map.of("#id", "userId", "#account", "accountId", "#email", "email"))
                .expressionValues(Map.of(":account", AttributeValue.fromS(user.accountId()),
                        ":email", AttributeValue.fromS(user.email()))).build());
    }
}
