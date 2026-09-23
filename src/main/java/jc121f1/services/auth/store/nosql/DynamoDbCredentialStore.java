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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import javax.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

@Slf4j
public final class DynamoDbCredentialStore extends DynamoDbStore<Credential> implements CredentialStore {

    private static final String TABLE_NAME = "MiniCloudCredentialStore";

    @Inject
    public DynamoDbCredentialStore(final DynamoDbAsyncClient dynamoDbClient) {
        super(dynamoDbClient, createDefinition(TABLE_NAME));
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbCredentialStore(final DynamoDbAsyncClient dynamoDbClient, String tableName) {
        super(dynamoDbClient, createDefinition(tableName));
        initialize().join();
    }

    @VisibleForTesting
    public DynamoDbCredentialStore(
            final DynamoDbAsyncClient dynamoDbAsyncClient,
            final DynamoDbAsyncTable<Credential> table
    ) {
        super(dynamoDbAsyncClient, table, createDefinition(TABLE_NAME));
    }

    @Override
    public CompletableFuture<Optional<Credential>> get(String id) {
        return super.get(id, true);
    }

    @Override
    public CompletableFuture<Credential> update(Credential previous, Credential updated, Expression condition) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(updated, "updated");
        if (!Objects.equals(previous.createdByUserId(), updated.createdByUserId())
                || !Objects.equals(previous.accountId(), updated.accountId())) {
            throw new IllegalArgumentException("Credential creator and account cannot be changed during update");
        }
        if (previous.revoked() && !updated.revoked()) {
            throw new IllegalArgumentException("Revoked credentials cannot be reactivated");
        }
        if (!updated.revoked()) {
            String guard = "(attribute_not_exists(#credentialRevokedGuard) "
                    + "OR #credentialRevokedGuard = :credentialActiveGuard)";
            if (condition == null) {
                condition = Expression.builder().expression(guard)
                        .expressionNames(Map.of("#credentialRevokedGuard", "revoked"))
                        .expressionValues(Map.of(":credentialActiveGuard", AttributeValue.builder().bool(false).build()))
                        .build();
            } else {
                Map<String, String> names = new HashMap<>(condition.expressionNames() == null
                        ? Map.of() : condition.expressionNames());
                Map<String, AttributeValue> values = new HashMap<>(condition.expressionValues() == null
                        ? Map.of() : condition.expressionValues());
                if (names.putIfAbsent("#credentialRevokedGuard", "revoked") != null
                        || values.putIfAbsent(":credentialActiveGuard", AttributeValue.builder().bool(false).build()) != null) {
                    throw new IllegalArgumentException("Credential guard expression placeholders are reserved");
                }
                condition = Expression.builder().expression("(" + condition.expression() + ") AND " + guard)
                        .expressionNames(names).expressionValues(values).build();
            }
        }
        return super.update(previous, updated, condition);
    }

    private static DynamoDbStoreDefinition<Credential> createDefinition(String tableName) {
        return new DynamoDbStoreDefinition<>(
                tableName,
                TableSchema.fromImmutableClass(Credential.class),
                Credential::credentialId,
                List.of(),
                List.of()
        );
    }
}
