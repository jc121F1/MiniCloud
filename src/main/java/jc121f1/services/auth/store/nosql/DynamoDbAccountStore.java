package jc121f1.services.auth.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.common.store.nosql.DynamoDbStoreDefinition;
import jc121f1.common.store.nosql.UniqueConstraint;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import javax.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Slf4j
public final class DynamoDbAccountStore extends DynamoDbStore<Account> implements AccountStore {

    private static final String TABLE_NAME = "MiniCloudIdentityStore";
    private final DynamoDbUserStore users;

    @Inject
    public DynamoDbAccountStore(final DynamoDbAsyncClient dynamoDbClient, DynamoDbUserStore users) {
        super(dynamoDbClient, createDefinition(TABLE_NAME));
        this.users = users;
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbAccountStore(final DynamoDbAsyncClient dynamoDbClient, DynamoDbUserStore users, String tableName) {
        super(dynamoDbClient, createDefinition(tableName));
        this.users = users;
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbAccountStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<Account> table, DynamoDbUserStore users
    ) {
        super(dynamoDbAsyncClient, table, createDefinition(TABLE_NAME));
        this.users = users;
    }

    @Override
    public CompletableFuture<Optional<Account>> get(String id) {
        return super.get(id, true);
    }

    @Override
    public CompletableFuture<Account> transferOwnership(Account observed, User proposedOwner, Instant updatedAt) {
        if (!observed.accountId().equals(proposedOwner.accountId())) {
            throw new IllegalArgumentException("Proposed owner must belong to account");
        }
        Expression ownerGuard = Expression.builder()
                .expression("#owner = :owner AND #status = :active")
                .expressionNames(Map.of("#owner", "ownerId", "#status", "status"))
                .expressionValues(Map.of(":owner", AttributeValue.fromS(observed.ownerId()),
                        ":active", AttributeValue.fromS(Account.AccountStatus.ACTIVE.name()))).build();
        var items = new ArrayList<TransactWriteItem>();
        if (observed.ownerId().equals(proposedOwner.userId())) {
            items.add(checkItem(observed, ownerGuard));
        } else {
            items.addAll(updateItems(observed, observed.toBuilder().ownerId(proposedOwner.userId())
                    .updatedAt(updatedAt).build(), ownerGuard));
        }
        items.add(users.sameAccountCheck(proposedOwner));
        Account result = observed.ownerId().equals(proposedOwner.userId()) ? observed
                : observed.toBuilder().ownerId(proposedOwner.userId()).updatedAt(updatedAt).build();
        return mutation(transact(items)).thenApply(ignored -> result);
    }

    @Override
    public CompletableFuture<Void> deleteUserIfNotOwner(User user) {
        Account key = Account.builder().accountId(user.accountId()).build();
        Expression guard = Expression.builder().expression("attribute_exists(#id) AND #owner <> :user")
                .expressionNames(Map.of("#id", "accountId", "#owner", "ownerId"))
                .expressionValues(Map.of(":user", AttributeValue.fromS(user.userId()))).build();
        var items = new ArrayList<TransactWriteItem>();
        items.add(checkItem(key, guard));
        items.addAll(users.guardedDeleteItems(user));
        return mutation(transact(items));
    }

    private static <T> CompletableFuture<T> mutation(CompletableFuture<T> operation) {
        return operation.exceptionallyCompose(error -> {
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof TransactionCanceledException cancelled && cancelled.cancellationReasons() != null
                    && cancelled.cancellationReasons().stream().anyMatch(reason ->
                    "ConditionalCheckFailed".equals(reason.code()) || "TransactionConflict".equals(reason.code()))) {
                return CompletableFuture.failedFuture(new PolicyConflictException("Identity changed; retry with current state"));
            }
            return CompletableFuture.failedFuture(new AuthorizationStoreException("Identity mutation failed", cause));
        });
    }

    private static DynamoDbStoreDefinition<Account> createDefinition(String tableName) {
        return new DynamoDbStoreDefinition<>(
                tableName,
                TableSchema.fromImmutableClass(Account.class),
                Account::accountId,
                List.of(new UniqueConstraint<>("accountId", Account::accountId)),
                List.of()
        );
    }
}
