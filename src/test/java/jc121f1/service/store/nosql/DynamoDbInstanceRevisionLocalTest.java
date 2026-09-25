package jc121f1.service.store.nosql;

import jc121f1.model.instance.InstanceState;
import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.exceptions.ConflictException;
import jc121f1.services.instance.store.nosql.DynamoDbInstanceStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DynamoDbLocalAvailable", matches = "True")
class DynamoDbInstanceRevisionLocalTest {
    private final String tableName = "InstanceRevisionTest-" + UUID.randomUUID();
    private DynamoDbAsyncClient client;
    private DynamoDbInstanceStore store;

    @BeforeAll
    void initialize() {
        client = DynamoDbAsyncClient.builder().endpointOverride(URI.create("http://localhost:8000"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")))
                .build();
        store = new DynamoDbInstanceStore(client, tableName);
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

    @Test
    void legacyRevisionUpgradesAndRejectsStaleWritesAfterStateReturns() {
        Instance legacy = create();
        Assertions.assertThat(store.get(legacy.id(), true).join().orElseThrow().revision()).isNull();
        Instance starting = store.update(legacy, legacy.toBuilder().state(InstanceState.STARTING).build()).join();
        Assertions.assertThat(starting.revision()).isEqualTo(1L);
        Instance stopped = store.update(starting, starting.toBuilder().state(InstanceState.STOPPED).build()).join();
        Assertions.assertThat(stopped.revision()).isEqualTo(2L);

        Assertions.assertThatThrownBy(() -> store.update(legacy, legacy.toBuilder()
                        .state(InstanceState.MISSING).build()).join()).hasCauseInstanceOf(ConflictException.class);
        Assertions.assertThatThrownBy(() -> store.delete(legacy).join()).hasCauseInstanceOf(ConflictException.class);
        Assertions.assertThat(store.get(legacy.id(), true).join()).contains(stopped);
        Assertions.assertThatThrownBy(() -> store.create(legacy.toBuilder()
                .id("i-" + UUID.randomUUID()).build()).join()).isInstanceOf(RuntimeException.class);
        store.delete(stopped).join();
        // Failed stale deletion must leave the name lock intact; successful deletion releases it.
        store.create(legacy.toBuilder().id("i-" + UUID.randomUUID()).build()).join();
    }

    @Test
    void onlyOneConcurrentTransitionWins() {
        Instance original = create();
        CompletableFuture<Instance> first = store.update(original, original.toBuilder()
                .state(InstanceState.STARTING).build());
        CompletableFuture<Instance> second = store.update(original, original.toBuilder()
                .state(InstanceState.MISSING).build());
        CompletableFuture.allOf(first, second).handle((ignored, error) -> null).join();
        Assertions.assertThat(first.isCompletedExceptionally() ^ second.isCompletedExceptionally()).isTrue();
        Assertions.assertThat(store.get(original.id(), true).join().orElseThrow().revision()).isEqualTo(1L);
    }

    @Test
    void deletionAndStartCompeteForTheSamePersistedRevision() {
        Instance original = create();
        CompletableFuture<Instance> start = store.update(original, original.toBuilder()
                .state(InstanceState.STARTING).build());
        CompletableFuture<Instance> deletion = store.update(original, original.toBuilder()
                .state(InstanceState.DELETING).build());
        CompletableFuture.allOf(start, deletion).handle((ignored, error) -> null).join();
        Assertions.assertThat(start.isCompletedExceptionally() ^ deletion.isCompletedExceptionally()).isTrue();
        Instance winner = store.get(original.id(), true).join().orElseThrow();
        Assertions.assertThat(winner.state()).isEqualTo(start.isCompletedExceptionally()
                ? InstanceState.DELETING : InstanceState.STARTING);
        Assertions.assertThat(winner.revision()).isEqualTo(1L);
        Assertions.assertThatThrownBy(() -> store.update(original, original.toBuilder()
                .state(InstanceState.RUNNING).build()).join()).hasCauseInstanceOf(ConflictException.class);
        Assertions.assertThatThrownBy(() -> store.delete(original).join()).hasCauseInstanceOf(ConflictException.class);
    }

    @Test
    void staleCompletionCannotRecreateDeletedRow() {
        Instance original = create();
        store.delete(original).join();
        Assertions.assertThatThrownBy(() -> store.update(original, original.toBuilder()
                .state(InstanceState.RUNNING).build()).join()).hasCauseInstanceOf(ConflictException.class);
        Assertions.assertThat(store.get(original.id(), true).join()).isEmpty();
    }

    private Instance create() {
        String id = "i-" + UUID.randomUUID();
        return store.create(Instance.builder().id(id).name(id).accountId("a-1")
                .state(InstanceState.STOPPED).build()).join();
    }

    @Test
    void suppliedConditionsAndOwnerGuardCannotBypassRevisionChecks() {
        Instance original = create();
        Expression impossible = Expression.builder().expression("attribute_not_exists(id)").build();
        Assertions.assertThatThrownBy(() -> store.update(original, original, impossible).join())
                .hasCauseInstanceOf(ConflictException.class);
        Assertions.assertThatThrownBy(() -> store.delete(original, impossible).join())
                .hasCauseInstanceOf(ConflictException.class);
        Assertions.assertThatThrownBy(() -> store.update(original,
                original.toBuilder().accountId("a-other").build(), impossible))
                .isInstanceOf(IllegalArgumentException.class);
        Instance current = store.update(original, original).join();
        Expression exists = Expression.builder().expression("attribute_exists(id)").build();
        Assertions.assertThatThrownBy(() -> store.update(original, original, exists).join())
                .hasCauseInstanceOf(ConflictException.class);
        Assertions.assertThatThrownBy(() -> store.delete(original, exists).join())
                .hasCauseInstanceOf(ConflictException.class);
        Assertions.assertThat(store.get(original.id(), true).join()).contains(current);
    }
}
