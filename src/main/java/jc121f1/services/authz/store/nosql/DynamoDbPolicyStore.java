package jc121f1.services.authz.store.nosql;

import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.common.store.nosql.DynamoDbStoreDefinition;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.model.authz.dao.PolicyRecord;
import jc121f1.services.authz.exceptions.AuthorizationStoreException;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import jc121f1.services.authz.exceptions.PolicyNotFoundException;
import jc121f1.services.authz.exceptions.PolicyValidationException;
import jc121f1.services.authz.store.PolicyStore;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Authz revision/attachment rules; mapping, CRUD, queries and transactions come from the common store. */
public final class DynamoDbPolicyStore extends DynamoDbStore<PolicyRecord> implements PolicyStore {
    private static final String TABLE_NAME = "MiniCloudAuthorizationStore";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final AttributeValue FALSE = AttributeValue.builder().bool(false).build();
    private static final AttributeValue TRUE = AttributeValue.builder().bool(true).build();

    @Inject
    public DynamoDbPolicyStore(DynamoDbAsyncClient client) {
        this(client, TABLE_NAME);
        initialize().join();
    }

    /** Explicit table name and initialization for isolated persistence tests. */
    public DynamoDbPolicyStore(DynamoDbAsyncClient client, String tableName) {
        super(client, new DynamoDbStoreDefinition<>(tableName, TableSchema.fromImmutableClass(PolicyRecord.class),
                PolicyRecord::pk, List.of(), List.of()));
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return protect(super.initialize());
    }

    @Override
    public CompletableFuture<Policy> create(Policy policy) {
        Objects.requireNonNull(policy, "policy");
        Key key = policyKey(policy.accountId(), policy.policyId());
        require(policy.revision() == 1, "New policies must have revision 1");
        Objects.requireNonNull(policy.document(), "document");
        PolicyRecord row = row(key, policy.policyId()).toBuilder().accountId(policy.accountId())
                .revision(1L).attachmentCount(0L).deleted(false).document(policy.document()).build();
        return protect(super.create(row).thenApply(ignored -> policy));
    }

    @Override
    public CompletableFuture<Optional<Policy>> get(String accountId, String policyId) {
        return protect(super.get(policyKey(accountId, policyId), true)
                .thenApply(item -> item.filter(row -> !Boolean.TRUE.equals(row.deleted()))
                        .map(row -> toPolicy(row, accountId))));
    }

    @Override
    public CompletableFuture<List<Policy>> list(String accountId) {
        validId(accountId);
        return protect(queryPartition("A#" + accountId).thenApply(rows -> rows.stream()
                .filter(row -> !Boolean.TRUE.equals(row.deleted())).map(row -> toPolicy(row, accountId)).toList()));
    }

    @Override
    public CompletableFuture<Policy> update(String accountId, String policyId, long expectedRevision, PolicyDocument document) {
        Key key = policyKey(accountId, policyId);
        validRevision(expectedRevision);
        require(expectedRevision < Long.MAX_VALUE, "Policy revision exhausted");
        Objects.requireNonNull(document, "document");
        Expression update = expression("SET #doc = :doc, #rev = :next", Map.of("#doc", "document", "#rev", "revision"),
                Map.of(":doc", attributes(row(key, policyId).toBuilder().document(document).build()).get("document"),
                        ":next", number(expectedRevision + 1)));
        return protect(onConditionalFailure(transact(List.of(updateAttributes(key, update, revisionCondition(expectedRevision))))
                .thenApply(ignored -> new Policy(policyId, accountId, expectedRevision + 1, document)), accountId, policyId));
    }

    @Override
    public CompletableFuture<Void> delete(String accountId, String policyId, long expectedRevision) {
        Key key = policyKey(accountId, policyId);
        validRevision(expectedRevision);
        Expression update = expression("SET #deleted = :true REMOVE #doc", Map.of("#deleted", "deleted", "#doc", "document"),
                Map.of(":true", TRUE));
        Expression condition = expression("#rev = :expected AND #deleted = :false AND #count = :zero",
                Map.of("#rev", "revision", "#deleted", "deleted", "#count", "attachmentCount"),
                Map.of(":expected", number(expectedRevision), ":false", FALSE, ":zero", number(0)));
        return protect(onConditionalFailure(transact(List.of(updateAttributes(key, update, condition))), accountId, policyId));
    }

    @Override
    public CompletableFuture<Void> attach(String accountId, String policyId, long expectedRevision, PrincipalReference principal) {
        Key policyKey = policyKey(accountId, policyId);
        Key attachmentKey = attachmentKey(accountId, policyId, principal);
        validRevision(expectedRevision);
        return protect(requirePolicy(accountId, policyId).thenCompose(policy -> {
            if (policy.revision() != expectedRevision) {
                return CompletableFuture.failedFuture(new PolicyConflictException("Stale policy revision"));
            }
            return super.get(attachmentKey, true).thenCompose(existing -> {
                if (existing.isPresent()) {
                    return CompletableFuture.completedFuture(null);
                }
                Expression condition = expression("#rev = :expected AND #deleted = :false AND #count >= :zero",
                        Map.of("#rev", "revision", "#deleted", "deleted", "#count", "attachmentCount"),
                        Map.of(":expected", number(expectedRevision), ":false", FALSE, ":zero", number(0)));
                List<TransactWriteItem> writes = new ArrayList<>();
                writes.add(updateAttributes(policyKey, counterChange(1), condition));
                writes.addAll(createItems(row(attachmentKey, policyId)));
                return transact(writes).exceptionallyCompose(error -> resolveAttachmentRace(error, accountId,
                        policyId, expectedRevision, attachmentKey, true));
            });
        }));
    }

    @Override
    public CompletableFuture<Void> detach(String accountId, String policyId, PrincipalReference principal) {
        Key policyKey = policyKey(accountId, policyId);
        Key attachmentKey = attachmentKey(accountId, policyId, principal);
        return protect(requirePolicy(accountId, policyId).thenCompose(policy -> super.get(attachmentKey, true).thenCompose(existing -> {
            if (existing.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            Expression condition = expression("#deleted = :false AND #count > :zero",
                    Map.of("#deleted", "deleted", "#count", "attachmentCount"), Map.of(":false", FALSE, ":zero", number(0)));
            List<TransactWriteItem> writes = new ArrayList<>();
            writes.add(updateAttributes(policyKey, counterChange(-1), condition));
            writes.addAll(deleteItems(row(attachmentKey, policyId), expression("attribute_exists(#pk)", Map.of("#pk", "pk"), Map.of())));
            return transact(writes).exceptionallyCompose(error -> resolveAttachmentRace(error, accountId, policyId, 0, attachmentKey, false));
        })));
    }

    @Override
    public CompletableFuture<List<Policy>> listAttached(PrincipalReference principal) {
        String partition = principalPartition(principal);
        return protect(queryPartition(partition).thenCompose(rows -> {
            List<CompletableFuture<Policy>> policies = rows.stream().map(row -> {
                if (row.policyId() == null || !ID.matcher(row.policyId()).matches() || !partition.equals(row.pk())
                        || !("P#" + row.policyId()).equals(row.sk())) {
                    throw new AuthorizationStoreException("Invalid stored policy attachment", null);
                }
                return get(principal.accountId(), row.policyId()).thenApply(policy -> policy.orElseThrow(() ->
                        new AuthorizationStoreException("Attachment refers to a missing policy; retry evaluation", null)));
            }).toList();
            return CompletableFuture.allOf(policies.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> policies.stream().map(CompletableFuture::join).toList());
        }));
    }

    private CompletableFuture<List<PolicyRecord>> queryPartition(String partition) {
        return super.query(QueryConditional.sortBeginsWith(Key.builder().partitionValue(partition).sortValue("P#").build()), true);
    }

    private CompletableFuture<Policy> requirePolicy(String accountId, String policyId) {
        return get(accountId, policyId).thenApply(policy -> policy.orElseThrow(() -> new PolicyNotFoundException("Policy not found")));
    }

    private CompletableFuture<Void> resolveAttachmentRace(Throwable error, String accountId, String policyId,
                                                          long revision, Key key, boolean attaching) {
        if (!conditionalConflict(unwrap(error))) {
            return CompletableFuture.failedFuture(unwrap(error));
        }
        return requirePolicy(accountId, policyId).thenCompose(policy -> {
            if (attaching && policy.revision() != revision) {
                return CompletableFuture.failedFuture(new PolicyConflictException("Stale policy revision"));
            }
            return super.get(key, true).thenCompose(item -> attaching == item.isPresent()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new PolicyConflictException("Concurrent attachment change; retry")));
        });
    }

    private <T> CompletableFuture<T> onConditionalFailure(CompletableFuture<T> operation, String accountId, String policyId) {
        return operation.exceptionallyCompose(error -> {
            if (conditionalConflict(unwrap(error))) {
                return requirePolicy(accountId, policyId).thenCompose(policy ->
                        CompletableFuture.failedFuture(new PolicyConflictException("Policy revision or attachment state changed")));
            }
            return CompletableFuture.failedFuture(unwrap(error));
        });
    }

    private static Policy toPolicy(PolicyRecord row, String accountId) {
        if (row.policyId() == null || !ID.matcher(row.policyId()).matches() || !accountId.equals(row.accountId())
                || !("A#" + accountId).equals(row.pk()) || !("P#" + row.policyId()).equals(row.sk())
                || row.revision() == null || row.revision() < 1 || row.attachmentCount() == null || row.attachmentCount() < 0
                || !Boolean.FALSE.equals(row.deleted()) || row.document() == null) {
            throw new AuthorizationStoreException("Invalid stored policy", null);
        }
        return new Policy(row.policyId(), row.accountId(), row.revision(), row.document());
    }

    private static Key policyKey(String accountId, String policyId) {
        validId(accountId);
        validId(policyId);
        return Key.builder().partitionValue("A#" + accountId).sortValue("P#" + policyId).build();
    }

    private static Key attachmentKey(String accountId, String policyId, PrincipalReference principal) {
        String partition = principalPartition(principal);
        require(accountId.equals(principal.accountId()), "Cross-account attachment");
        return Key.builder().partitionValue(partition).sortValue("P#" + policyId).build();
    }

    private static PolicyRecord row(Key key, String policyId) {
        return PolicyRecord.builder().pk(key.partitionKeyValue().s()).sk(key.sortKeyValue().orElseThrow().s()).policyId(policyId).build();
    }

    private static String principalPartition(PrincipalReference principal) {
        Objects.requireNonNull(principal, "principal");
        validId(principal.accountId());
        validId(principal.subjectId());
        Objects.requireNonNull(principal.subjectType(), "subjectType");
        return "A#" + principal.accountId() + "#S#" + principal.subjectType().name() + "#" + principal.subjectId();
    }

    private static Expression revisionCondition(long revision) {
        return expression("#rev = :expected AND #deleted = :false", Map.of("#rev", "revision", "#deleted", "deleted"),
                Map.of(":expected", number(revision), ":false", FALSE));
    }

    private static Expression counterChange(long delta) {
        return expression("ADD #count :delta", Map.of("#count", "attachmentCount"), Map.of(":delta", number(delta)));
    }

    private static Expression expression(String value, Map<String, String> names, Map<String, AttributeValue> values) {
        return Expression.builder().expression(value).expressionNames(names).expressionValues(values).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static void validId(String value) {
        require(value != null && ID.matcher(value).matches(), "Invalid account, policy, or principal ID");
    }

    private static void validRevision(long revision) {
        require(revision > 0, "Expected revision must be positive");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new PolicyValidationException(message);
        }
    }

    private static boolean conditionalConflict(Throwable error) {
        if (error instanceof ConditionalCheckFailedException || error instanceof TransactionConflictException) {
            return true;
        }
        if (error instanceof TransactionCanceledException canceled) {
            return !canceled.cancellationReasons().isEmpty()
                    && canceled.cancellationReasons().stream().allMatch(reason -> "None".equals(reason.code())
                            || "ConditionalCheckFailed".equals(reason.code()) || "TransactionConflict".equals(reason.code()))
                    && canceled.cancellationReasons().stream().anyMatch(reason -> !"None".equals(reason.code()));
        }
        return false;
    }

    private static <T> CompletableFuture<T> protect(CompletableFuture<T> future) {
        return future.handle((result, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(result);
            }
            Throwable cause = unwrap(error);
            if (cause instanceof PolicyNotFoundException || cause instanceof PolicyConflictException
                    || cause instanceof PolicyValidationException || cause instanceof AuthorizationStoreException) {
                return CompletableFuture.<T>failedFuture(cause);
            }
            if (conditionalConflict(cause)) {
                return CompletableFuture.<T>failedFuture(new PolicyConflictException("Concurrent policy change or reused policy ID"));
            }
            return CompletableFuture.<T>failedFuture(new AuthorizationStoreException("Authorization storage unavailable", cause));
        }).thenCompose(Function.identity());
    }
}
