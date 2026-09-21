package jc121f1.services.auth.store.nosql;

import com.google.common.annotations.VisibleForTesting;
import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.common.store.nosql.DynamoDbStoreDefinition;
import jc121f1.model.auth.dao.Credential;
import jc121f1.services.auth.store.CredentialStore;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import javax.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Slf4j
public final class DynamoDbCredentialStore extends DynamoDbStore<Credential> implements CredentialStore {

    private static final String TABLE_NAME = "MiniCloudCredentialStore";

    @Inject
    public DynamoDbCredentialStore(final DynamoDbAsyncClient dynamoDbClient) {
        super(dynamoDbClient, createDefinition());
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbCredentialStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<Credential> table
    ) {
        super(dynamoDbAsyncClient, table, createDefinition());
    }

    @Override
    public CompletableFuture<Credential> update(Credential previous, Credential updated, Expression condition) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(updated, "updated");
        if (!Objects.equals(previous.createdByUserId(), updated.createdByUserId())
                || !Objects.equals(previous.accountId(), updated.accountId())) {
            throw new IllegalArgumentException("Credential creator and account cannot be changed during update");
        }
        return super.update(previous, updated, condition);
    }

    private static DynamoDbStoreDefinition<Credential> createDefinition() {
        return new DynamoDbStoreDefinition<>(
                TABLE_NAME,
                TableSchema.fromImmutableClass(Credential.class),
                Credential::credentialId,
                List.of(),
                List.of()
        );
    }
}
