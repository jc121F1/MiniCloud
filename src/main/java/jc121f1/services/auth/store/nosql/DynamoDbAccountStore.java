package jc121f1.services.auth.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.common.store.nosql.DynamoDbStoreDefinition;
import jc121f1.common.store.nosql.UniqueConstraint;
import jc121f1.model.auth.dao.Account;
import jc121f1.services.auth.store.AccountStore;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import javax.inject.Inject;
import java.util.List;

@Slf4j
public final class DynamoDbAccountStore extends DynamoDbStore<Account> implements AccountStore {

    private static final String TABLE_NAME = "MiniCloudIdentityStore";

    @Inject
    public DynamoDbAccountStore(final DynamoDbAsyncClient dynamoDbClient) {
        super(dynamoDbClient, createDefinition());
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbAccountStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<Account> table
    ) {
        super(dynamoDbAsyncClient, table, createDefinition());
    }

    private static DynamoDbStoreDefinition<Account> createDefinition() {
        return new DynamoDbStoreDefinition<>(
                TABLE_NAME,
                TableSchema.fromImmutableClass(Account.class),
                Account::accountId,
                List.of(new UniqueConstraint<>("accountId", Account::accountId)),
                List.of()
        );
    }
}