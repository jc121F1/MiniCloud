package jc121f1.service.authz.store;

import jc121f1.model.auth.dao.Session;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.authz.dao.PolicyRecord;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import jc121f1.services.authz.exceptions.PolicyNotFoundException;
import jc121f1.services.authz.exceptions.PolicyValidationException;
import jc121f1.services.authz.store.nosql.DynamoDbPolicyStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.paginators.QueryPublisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class DynamoDbPolicyStoreTest {
    private static final String ACCOUNT = "a-1";
    private static final String POLICY_ID = "p-1";
    private static final PrincipalReference USER = new PrincipalReference(ACCOUNT, "u-1", Session.SubjectType.USER);
    private static final PolicyDocument DOCUMENT = new PolicyDocument(1, List.of(new PolicyDocument.Statement(
            PolicyDocument.Effect.ALLOW, List.of("instance:Start"), List.of("mc:instance:a-1:instance/*"))));
    private DynamoDbAsyncClient client;
    private DynamoDbPolicyStore store;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(DynamoDbAsyncClient.class);
        store = new DynamoDbPolicyStore(client, "PolicyStoreUnitTest");
        Mockito.when(client.queryPaginator(Mockito.any(QueryRequest.class)))
                .thenAnswer(invocation -> new QueryPublisher(client, invocation.getArgument(0)));
    }

    @Test
    void creates_revision_one_with_no_attachments_and_a_non_reuse_condition() {
        Mockito.when(client.transactWriteItems(Mockito.any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
        Policy policy = new Policy(POLICY_ID, ACCOUNT, 1, DOCUMENT);
        Assertions.assertThat(store.create(policy).join()).isEqualTo(policy);
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        Mockito.verify(client).transactWriteItems(request.capture());
        var put = request.getValue().transactItems().getFirst().put();
        Assertions.assertThat(put.conditionExpression()).isEqualTo("attribute_not_exists(#id)");
        Assertions.assertThat(put.expressionAttributeNames()).containsEntry("#id", "pk");
        Assertions.assertThat(put.item().get("document").hasM()).isTrue();
        Assertions.assertThat(put.item()).containsEntry("__record_type", text("ITEM")).containsEntry("attachmentCount", number(0))
                .containsEntry("revision", number(1)).containsEntry("pk", text("A#a-1"));
    }

    @Test
    void reads_policy_from_base_table_consistently() {
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class))).thenReturn(response(policyItem(3)));
        Assertions.assertThat(store.get(ACCOUNT, POLICY_ID).join()).contains(new Policy(POLICY_ID, ACCOUNT, 3, DOCUMENT));
        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        Mockito.verify(client).getItem(request.capture());
        Assertions.assertThat(request.getValue().consistentRead()).isTrue();
        Assertions.assertThat(request.getValue().key()).containsEntry("pk", text("A#a-1"));
    }

    @Test
    void query_paginates_even_when_a_page_is_empty_and_never_uses_an_index() {
        Map<String, AttributeValue> cursor = Map.of("pk", text("A#a-1"), "sk", text("P#cursor"));
        Mockito.when(client.query(Mockito.any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().lastEvaluatedKey(cursor).build()))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().items(policyItem(1)).build()));
        Assertions.assertThat(store.list(ACCOUNT).join()).containsExactly(new Policy(POLICY_ID, ACCOUNT, 1, DOCUMENT));
        ArgumentCaptor<QueryRequest> requests = ArgumentCaptor.forClass(QueryRequest.class);
        Mockito.verify(client, Mockito.times(2)).query(requests.capture());
        for (QueryRequest request : requests.getAllValues()) {
            Assertions.assertThat(request.consistentRead()).isTrue();
            Assertions.assertThat(request.indexName()).isNull();
        }
        Assertions.assertThat(requests.getAllValues().get(1).exclusiveStartKey()).isEqualTo(cursor);
    }

    @Test
    void updates_only_document_and_revision_preserving_attachment_count() {
        Mockito.when(client.transactWriteItems(Mockito.any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
        Assertions.assertThat(store.update(ACCOUNT, POLICY_ID, 3, DOCUMENT).join().revision()).isEqualTo(4);
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        Mockito.verify(client).transactWriteItems(request.capture());
        var update = request.getValue().transactItems().getFirst().update();
        Assertions.assertThat(update.expressionAttributeValues()).containsEntry(":expected", number(3));
        Assertions.assertThat(update.updateExpression()).isEqualTo("SET #doc = :doc, #rev = :next");
    }

    @Test
    void conditional_update_distinguishes_stale_revision_from_missing_policy() {
        Mockito.when(client.transactWriteItems(Mockito.any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class)))
                .thenReturn(response(policyItem(2))).thenReturn(response(Map.of()));
        Assertions.assertThatThrownBy(() -> store.update(ACCOUNT, POLICY_ID, 1, DOCUMENT).join())
                .hasCauseInstanceOf(PolicyConflictException.class);
        Assertions.assertThatThrownBy(() -> store.update(ACCOUNT, POLICY_ID, 1, DOCUMENT).join())
                .hasCauseInstanceOf(PolicyNotFoundException.class);
    }

    @Test
    void attachment_and_counter_are_written_in_one_conditional_transaction() {
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class)))
                .thenReturn(response(policyItem(1))).thenReturn(response(Map.of()));
        Mockito.when(client.transactWriteItems(Mockito.any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
        store.attach(ACCOUNT, POLICY_ID, 1, USER).join();
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        Mockito.verify(client).transactWriteItems(request.capture());
        var items = request.getValue().transactItems();
        Assertions.assertThat(items).hasSize(2);
        Assertions.assertThat(items.get(0).update().expressionAttributeValues()).containsEntry(":expected", number(1));
        Assertions.assertThat(items.get(0).update().updateExpression()).isEqualTo("ADD #count :delta");
        Assertions.assertThat(items.get(0).update().expressionAttributeValues()).containsEntry(":delta", number(1));
        Assertions.assertThat(items.get(1).put().item()).containsEntry("pk", text("A#a-1#S#USER#u-1"));
        Assertions.assertThat(items.get(1).put().conditionExpression()).isEqualTo("attribute_not_exists(#id)");
    }

    @Test
    void detach_updates_counter_and_removes_attachment_in_one_transaction() {
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class)))
                .thenReturn(response(policyItem(1))).thenReturn(response(attachment()));
        Mockito.when(client.transactWriteItems(Mockito.any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
        store.detach(ACCOUNT, POLICY_ID, USER).join();
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        Mockito.verify(client).transactWriteItems(request.capture());
        var items = request.getValue().transactItems();
        Assertions.assertThat(items).hasSize(2);
        Assertions.assertThat(items.get(0).update().expressionAttributeValues()).containsEntry(":delta", number(-1));
        Assertions.assertThat(items.get(0).update().conditionExpression()).contains("#count > :zero");
        Assertions.assertThat(items.get(1).delete().conditionExpression()).isEqualTo("attribute_exists(#pk)");
    }

    @Test
    void a_duplicate_attach_does_not_increment_count_but_still_checks_revision() {
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class)))
                .thenReturn(response(policyItem(1))).thenReturn(response(attachment())).thenReturn(response(policyItem(2)));
        store.attach(ACCOUNT, POLICY_ID, 1, USER).join();
        Assertions.assertThatThrownBy(() -> store.attach(ACCOUNT, POLICY_ID, 1, USER).join())
                .hasCauseInstanceOf(PolicyConflictException.class);
        Mockito.verify(client, Mockito.never()).transactWriteItems(Mockito.any(TransactWriteItemsRequest.class));
    }

    @Test
    void racing_duplicate_attach_resolves_idempotently_after_transaction_cancellation() {
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class)))
                .thenReturn(response(policyItem(1))).thenReturn(response(Map.of()))
                .thenReturn(response(policyItem(1))).thenReturn(response(attachment()));
        Mockito.when(client.transactWriteItems(Mockito.any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(TransactionCanceledException.builder()
                        .cancellationReasons(CancellationReason.builder().code("None").build(),
                                CancellationReason.builder().code("ConditionalCheckFailed").build()).build()));
        store.attach(ACCOUNT, POLICY_ID, 1, USER).join();
        Mockito.verify(client).transactWriteItems(Mockito.any(TransactWriteItemsRequest.class));
    }

    @Test
    void storage_failures_and_throttling_are_not_permission_denials_or_conflicts() {
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")))
                .thenReturn(response(policyItem(1))).thenReturn(response(Map.of()));
        Assertions.assertThatThrownBy(() -> store.get(ACCOUNT, POLICY_ID).join())
                .hasCauseInstanceOf(AuthorizationStoreException.class);
        Mockito.when(client.transactWriteItems(Mockito.any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(TransactionCanceledException.builder()
                        .cancellationReasons(CancellationReason.builder().code("ProvisionedThroughputExceeded").build()).build()));
        Assertions.assertThatThrownBy(() -> store.attach(ACCOUNT, POLICY_ID, 1, USER).join())
                .hasCauseInstanceOf(AuthorizationStoreException.class);
    }

    @Test
    void attached_policies_use_fresh_consistent_reads_and_missing_targets_fail_closed() {
        Mockito.when(client.query(Mockito.any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().items(attachment()).build()));
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class)))
                .thenReturn(response(policyItem(2))).thenReturn(response(Map.of()));
        Assertions.assertThat(store.listAttached(USER).join()).containsExactly(new Policy(POLICY_ID, ACCOUNT, 2, DOCUMENT));
        Assertions.assertThatThrownBy(() -> store.listAttached(USER).join()).hasCauseInstanceOf(AuthorizationStoreException.class);
        ArgumentCaptor<GetItemRequest> requests = ArgumentCaptor.forClass(GetItemRequest.class);
        Mockito.verify(client, Mockito.times(2)).getItem(requests.capture());
        Assertions.assertThat(requests.getAllValues()).allMatch(GetItemRequest::consistentRead);
    }

    @Test
    void malformed_persisted_documents_and_wrong_account_metadata_fail_closed() {
        Map<String, AttributeValue> invalid = new HashMap<>(policyItem(1));
        invalid.put("document", text("{broken"));
        Map<String, AttributeValue> otherAccount = new HashMap<>(policyItem(1));
        otherAccount.put("accountId", text("a-other"));
        Mockito.when(client.getItem(Mockito.any(GetItemRequest.class)))
                .thenReturn(response(invalid)).thenReturn(response(otherAccount));
        Assertions.assertThatThrownBy(() -> store.get(ACCOUNT, POLICY_ID).join()).hasCauseInstanceOf(AuthorizationStoreException.class);
        Assertions.assertThatThrownBy(() -> store.get(ACCOUNT, POLICY_ID).join()).hasCauseInstanceOf(AuthorizationStoreException.class);
    }

    @Test
    void rejects_cross_account_targets_and_key_injection_before_storage_access() {
        Assertions.assertThatThrownBy(() -> store.attach(ACCOUNT, POLICY_ID, 1,
                new PrincipalReference("a-other", "u-1", Session.SubjectType.USER))).isInstanceOf(PolicyValidationException.class);
        Assertions.assertThatThrownBy(() -> store.get("a-1#S#USER", POLICY_ID)).isInstanceOf(PolicyValidationException.class);
        Assertions.assertThatThrownBy(() -> store.update(ACCOUNT, POLICY_ID, 0, DOCUMENT)).isInstanceOf(PolicyValidationException.class);
        Assertions.assertThatThrownBy(() -> store.update(ACCOUNT, POLICY_ID, Long.MAX_VALUE, DOCUMENT))
                .isInstanceOf(PolicyValidationException.class);
        Mockito.verifyNoInteractions(client);
    }

    private static Map<String, AttributeValue> policyItem(long revision) {
        return TableSchema.fromImmutableClass(PolicyRecord.class).itemToMap(PolicyRecord.builder()
                .pk("A#" + ACCOUNT).sk("P#" + POLICY_ID).accountId(ACCOUNT).policyId(POLICY_ID)
                .revision(revision).attachmentCount(0L).deleted(false).document(DOCUMENT).build(), true);
    }

    private static Map<String, AttributeValue> attachment() {
        return Map.of("pk", text("A#a-1#S#USER#u-1"), "sk", text("P#p-1"), "policyId", text(POLICY_ID));
    }

    private static CompletableFuture<GetItemResponse> response(Map<String, AttributeValue> item) {
        return CompletableFuture.completedFuture(GetItemResponse.builder().item(item).build());
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
