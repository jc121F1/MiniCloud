package jc121f1.common.store.nosql;

import lombok.Builder;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class DynamoDbStoreExtensionsTest {
    private DynamoDbAsyncClient client;
    private CompositeStore store;
    private final Row original = new Row("partition", "row", 1L);

    @BeforeEach
    void setup() {
        client = Mockito.mock(DynamoDbAsyncClient.class);
        store = new CompositeStore(client);
    }

    @Test
    void full_primary_key_reads_propagate_strong_consistency() {
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));
        Assertions.assertThat(store.get(Key.builder().partitionValue("partition").sortValue("row").build(), true).join()).isEmpty();
        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        Mockito.verify(client).getItem(request.capture());
        Assertions.assertThat(request.getValue().consistentRead()).isTrue();
        Assertions.assertThat(request.getValue().key()).containsKeys("pk", "sk");
        Assertions.assertThatThrownBy(() -> store.get("partition", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conditional_replacement_preserves_existence_guard_and_discriminator() {
        successfulTransaction();
        Row updated = new Row("partition", "row", 2L);
        store.update(original, updated, versionCondition()).join();
        var put = transaction().transactItems().getFirst().put();
        Assertions.assertThat(put.conditionExpression()).contains("attribute_exists(#id)", "#version = :expected");
        Assertions.assertThat(put.expressionAttributeNames()).containsEntry("#id", "pk").containsEntry("#version", "version");
        Assertions.assertThat(put.expressionAttributeValues()).containsEntry(":expected", number(1));
        Assertions.assertThat(put.item()).containsEntry("__record_type", AttributeValue.builder().s("ITEM").build());
    }

    @Test
    void conditional_deletion_uses_both_key_components() {
        successfulTransaction();
        store.delete(original, versionCondition()).join();
        var delete = transaction().transactItems().getFirst().delete();
        Assertions.assertThat(delete.key()).containsKeys("pk", "sk");
        Assertions.assertThat(delete.conditionExpression()).isEqualTo("#version = :expected");
    }

    @Test
    void valueless_conditions_do_not_send_invalid_empty_expression_maps() {
        successfulTransaction();
        store.delete(original, Expression.builder().expression("attribute_exists(pk)")
                .expressionNames(Map.of()).expressionValues(Map.of()).build()).join();
        var delete = transaction().transactItems().getFirst().delete();
        Assertions.assertThat(delete.hasExpressionAttributeNames()).isFalse();
        Assertions.assertThat(delete.hasExpressionAttributeValues()).isFalse();
    }

    @Test
    void prevents_sort_key_changes_and_conflicting_expression_placeholders() {
        Assertions.assertThatThrownBy(() -> store.update(original, new Row("partition", "different", 2L)))
                .isInstanceOf(IllegalArgumentException.class);
        Expression collision = Expression.builder().expression("#id = :expected")
                .expressionNames(Map.of("#id", "version")).expressionValues(Map.of(":expected", number(1))).build();
        Assertions.assertThatThrownBy(() -> store.update(original, original, collision))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Conflicting expression placeholder");
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void composes_partial_update_and_conditional_create_in_one_transaction() {
        successfulTransaction();
        var patch = store.patch(original, Expression.builder().expression("SET #version = :next")
                .expressionNames(Map.of("#version", "version")).expressionValues(Map.of(":next", number(2))).build(), versionCondition());
        var create = store.insert(new Row("partition", "another", 1L)).getFirst();
        store.commit(List.of(patch, create)).join();
        var writes = transaction().transactItems();
        Assertions.assertThat(writes).hasSize(2);
        Assertions.assertThat(writes.getFirst().update().expressionAttributeValues())
                .containsEntry(":next", number(2)).containsEntry(":expected", number(1));
        Assertions.assertThat(writes.get(1).put().conditionExpression()).isEqualTo("attribute_not_exists(#id)");
    }

    @Test
    void rejects_empty_or_oversized_transactions_before_sending() {
        TransactWriteItem item = store.insert(original).getFirst();
        Assertions.assertThatThrownBy(() -> store.commit(List.of())).isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() -> store.commit(Collections.nCopies(101, item))).isInstanceOf(IllegalArgumentException.class);
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void composite_definitions_reject_unsupported_unique_lock_keys() {
        Assertions.assertThatThrownBy(() -> new DynamoDbStoreDefinition<>("test", TableSchema.fromImmutableClass(Row.class),
                Row::pk, List.of(new UniqueConstraint<Row>("sort", Row::sk)), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("partition-only");
    }

    private void successfulTransaction() {
        Mockito.when(client.transactWriteItems(Mockito.any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
    }

    private TransactWriteItemsRequest transaction() {
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        Mockito.verify(client).transactWriteItems(request.capture());
        return request.getValue();
    }

    private static Expression versionCondition() {
        return Expression.builder().expression("#version = :expected").expressionNames(Map.of("#version", "version"))
                .expressionValues(Map.of(":expected", number(1))).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static final class CompositeStore extends DynamoDbStore<Row> {
        private CompositeStore(DynamoDbAsyncClient client) {
            super(client, new DynamoDbStoreDefinition<>("test", TableSchema.fromImmutableClass(Row.class), Row::pk, List.of(), List.of()));
        }

        private List<TransactWriteItem> insert(Row row) {
            return createItems(row);
        }

        private TransactWriteItem patch(Row row, Expression update, Expression condition) {
            return updateAttributes(keyOf(row), update, condition);
        }

        private CompletableFuture<Void> commit(List<TransactWriteItem> items) {
            return transact(items);
        }
    }

    @Builder
    @DynamoDbImmutable(builder = Row.RowBuilder.class)
    public record Row(@DynamoDbPartitionKey String pk, @DynamoDbSortKey String sk, Long version) {
    }
}
