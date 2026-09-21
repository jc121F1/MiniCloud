package jc121f1.service.authz.store;

import jc121f1.model.auth.dao.Session;
import jc121f1.model.authz.Policy;
import jc121f1.model.authz.PolicyDocument;
import jc121f1.model.authz.PrincipalReference;
import jc121f1.services.authz.exceptions.PolicyConflictException;
import jc121f1.services.authz.exceptions.PolicyNotFoundException;
import jc121f1.services.authz.store.nosql.DynamoDbPolicyStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

/** Tests actual DynamoDB conditions/transactions; uses and removes only its unique test table. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DynamoDbLocalAvailable", matches = "True")
class DynamoDbPolicyStoreLocalTest {
    private final String tableName = "AuthzPolicyTest-" + UUID.randomUUID();
    private DynamoDbAsyncClient client;
    private DynamoDbPolicyStore store;
    private String account;
    private PrincipalReference user;

    @BeforeAll
    void initialize() {
        client = DynamoDbAsyncClient.builder().endpointOverride(URI.create("http://localhost:8000"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy"))).build();
        store = new DynamoDbPolicyStore(client, tableName);
        store.initialize().join();
        store.initialize().join();
    }

    @AfterAll
    void cleanup() {
        if (client != null) {
            try {
                client.deleteTable(request -> request.tableName(tableName)).join();
            } finally {
                client.close();
            }
        }
    }

    @BeforeEach
    void newAccount() {
        account = "a-" + UUID.randomUUID();
        user = new PrincipalReference(account, "u-1", Session.SubjectType.USER);
    }

    @Test
    void round_trip_revision_checks_and_deleted_id_reuse() {
        Policy original = create("p-1");
        Assertions.assertThat(store.get(account, "p-1").join()).contains(original);
        Policy replacement = store.update(account, "p-1", 1, document("instance:Stop")).join();
        Assertions.assertThat(replacement.revision()).isEqualTo(2);
        Assertions.assertThat(store.list(account).join()).containsExactly(replacement);
        Assertions.assertThatThrownBy(() -> store.update(account, "p-1", 1, original.document()).join())
                .hasCauseInstanceOf(PolicyConflictException.class);
        Assertions.assertThatThrownBy(() -> store.delete(account, "p-1", 1).join()).hasCauseInstanceOf(PolicyConflictException.class);
        store.delete(account, "p-1", 2).join();
        Assertions.assertThat(store.get(account, "p-1").join()).isEmpty();
        Assertions.assertThat(store.list(account).join()).isEmpty();
        Assertions.assertThatThrownBy(() -> store.create(original).join()).hasCauseInstanceOf(PolicyConflictException.class);
        Assertions.assertThatThrownBy(() -> store.update(account, "missing", 1, original.document()).join())
                .hasCauseInstanceOf(PolicyNotFoundException.class);
    }

    @Test
    void attachments_follow_updates_and_detach_removes_access_immediately() {
        create("p-1");
        store.attach(account, "p-1", 1, user).join();
        store.attach(account, "p-1", 1, user).join();
        Assertions.assertThatThrownBy(() -> store.delete(account, "p-1", 1).join()).hasCauseInstanceOf(PolicyConflictException.class);
        Policy replacement = store.update(account, "p-1", 1, document("instance:Stop")).join();
        Assertions.assertThat(store.listAttached(user).join()).containsExactly(replacement);
        Assertions.assertThatThrownBy(() -> store.attach(account, "p-1", 1, user).join()).hasCauseInstanceOf(PolicyConflictException.class);
        store.detach(account, "p-1", user).join();
        store.detach(account, "p-1", user).join();
        Assertions.assertThat(store.listAttached(user).join()).isEmpty();
        store.delete(account, "p-1", 2).join();
    }

    @Test
    void accounts_and_principal_types_are_isolated() {
        Policy policy = create("p-1");
        String other = "a-" + UUID.randomUUID();
        Policy otherPolicy = new Policy("p-1", other, 1, new PolicyDocument(1, List.of(new PolicyDocument.Statement(
                PolicyDocument.Effect.ALLOW, List.of("instance:Stop"), List.of("mc:instance:" + other + ":instance/*")))));
        store.create(otherPolicy).join();
        PrincipalReference otherUser = new PrincipalReference(other, user.subjectId(), Session.SubjectType.USER);
        PrincipalReference credential = new PrincipalReference(account, user.subjectId(), Session.SubjectType.CREDENTIAL);
        store.attach(account, "p-1", 1, user).join();
        Assertions.assertThat(store.list(other).join()).containsExactly(otherPolicy);
        Assertions.assertThat(store.listAttached(otherUser).join()).isEmpty();
        Assertions.assertThat(store.listAttached(credential).join()).isEmpty();
        store.attach(account, "p-1", 1, credential).join();
        store.detach(account, "p-1", user).join();
        Assertions.assertThat(store.listAttached(credential).join()).containsExactly(policy);
        Assertions.assertThatThrownBy(() -> store.delete(account, "p-1", 1).join()).hasCauseInstanceOf(PolicyConflictException.class);
        store.detach(account, "p-1", credential).join();
        store.delete(account, "p-1", 1).join();
    }

    @Test
    void only_one_concurrent_update_can_win_a_revision() {
        create("p-1");
        List<CompletableFuture<Policy>> writes = IntStream.range(0, 8)
                .mapToObj(index -> store.update(account, "p-1", 1, document(index % 2 == 0 ? "instance:Stop" : "instance:Start")))
                .toList();
        List<Throwable> errors = outcomes(writes);
        Assertions.assertThat(errors.stream().filter(Objects::isNull).count()).isEqualTo(1);
        Assertions.assertThat(errors.stream().filter(Objects::nonNull).toList()).allMatch(PolicyConflictException.class::isInstance);
        Assertions.assertThat(store.get(account, "p-1").join().orElseThrow().revision()).isEqualTo(2);
    }

    @Test
    void concurrent_duplicate_attach_and_detach_do_not_drift_the_counter() {
        create("p-1");
        List<CompletableFuture<Void>> attaches = IntStream.range(0, 8)
                .mapToObj(index -> store.attach(account, "p-1", 1, user)).toList();
        assertOnlySuccessOrConflict(outcomes(attaches));
        store.attach(account, "p-1", 1, user).join();
        Assertions.assertThat(store.listAttached(user).join()).hasSize(1);
        List<CompletableFuture<Void>> detaches = IntStream.range(0, 8)
                .mapToObj(index -> store.detach(account, "p-1", user)).toList();
        assertOnlySuccessOrConflict(outcomes(detaches));
        store.detach(account, "p-1", user).join();
        Assertions.assertThat(store.listAttached(user).join()).isEmpty();
        store.delete(account, "p-1", 1).join();
    }

    @Test
    void concurrent_distinct_attachments_are_counted_independently() {
        create("p-1");
        PrincipalReference second = new PrincipalReference(account, "u-2", Session.SubjectType.USER);
        assertOnlySuccessOrConflict(outcomes(List.of(store.attach(account, "p-1", 1, user), store.attach(account, "p-1", 1, second))));
        store.attach(account, "p-1", 1, user).join();
        store.attach(account, "p-1", 1, second).join();
        store.detach(account, "p-1", user).join();
        Assertions.assertThatThrownBy(() -> store.delete(account, "p-1", 1).join()).hasCauseInstanceOf(PolicyConflictException.class);
        Assertions.assertThat(store.listAttached(second).join()).hasSize(1);
        store.detach(account, "p-1", second).join();
        store.delete(account, "p-1", 1).join();
    }

    @Test
    void attach_delete_race_cannot_leave_an_attachment_to_a_deleted_policy() {
        for (int index = 0; index < 8; index++) {
            String policyId = "p-race-" + index;
            create(policyId);
            List<Throwable> errors = outcomes(List.of(store.attach(account, policyId, 1, user), store.delete(account, policyId, 1)));
            Assertions.assertThat(errors.stream().filter(Objects::nonNull).toList())
                    .allMatch(error -> error instanceof PolicyConflictException || error instanceof PolicyNotFoundException);
            if (store.get(account, policyId).join().isEmpty()) {
                Assertions.assertThat(store.listAttached(user).join()).noneMatch(policy -> policy.policyId().equals(policyId));
            } else {
                store.detach(account, policyId, user).join();
                store.delete(account, policyId, 1).join();
            }
        }
    }

    private Policy create(String policyId) {
        return store.create(new Policy(policyId, account, 1, document("instance:Start"))).join();
    }

    private PolicyDocument document(String action) {
        return new PolicyDocument(1, List.of(new PolicyDocument.Statement(PolicyDocument.Effect.ALLOW,
                List.of(action), List.of("mc:instance:" + account + ":instance/*"))));
    }

    private static List<Throwable> outcomes(List<? extends CompletableFuture<?>> operations) {
        return operations.stream().map(operation -> operation.handle((value, error) -> {
            Throwable cause = error;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause;
        })).toList().stream().map(CompletableFuture::join).toList();
    }

    private static void assertOnlySuccessOrConflict(List<Throwable> errors) {
        Assertions.assertThat(errors.stream().filter(Objects::nonNull).toList()).allMatch(PolicyConflictException.class::isInstance);
    }
}
