package jc121f1.common.store.nosql;

import jc121f1.annotations.MiniCloudTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MiniCloudTest
@EnabledIfEnvironmentVariable(
        named = "DynamoDbLocalAvailable",
        matches = "True",
        disabledReason = "Disabled due to no local DynamoDBAvailableForTesting"
)
class DynamoDbStoreTest {

    private static final String TABLE_NAME = "DynamoDbStoreTest";

    private DynamoDbAsyncClient client;
    private TestStore store;

    @BeforeAll
    void setUpClass() {
        client = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create("http://localhost:8000"))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create("dummy", "dummy")
                        )
                )
                .build();

        store = new TestStore(
                client,
                definition()
        );

        store.initialize().join();
    }

    @AfterAll
    void tearDownClass() {
        try {
            client.deleteTable(request ->
                    request.tableName(TABLE_NAME)
            ).join();
        } finally {
            client.close();
        }
    }

    @BeforeEach
    void setUp() {
        clearTable();
    }

    // ---------------------------------------------------------------------
    // get
    // ---------------------------------------------------------------------

    @Test
    void getReturnsItem() {
        TestItem item = item(
                "1",
                "Jack",
                "jack@example.com"
        );

        store.create(item).join();

        assertThat(store.get("1").join())
                .contains(item);
    }

    @Test
    void getReturnsEmptyForMissingItem() {
        assertThat(store.get("missing").join())
                .isEmpty();
    }

    @Test
    void getRejectsNullId() {
        assertThatThrownBy(() -> store.get(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("id must not be null");
    }

    // ---------------------------------------------------------------------
    // list
    // ---------------------------------------------------------------------

    @Test
    void listReturnsAllItems() {
        TestItem first =
                item("1", "One", "one@example.com");

        TestItem second =
                item("2", "Two", "two@example.com");

        store.create(first).join();
        store.create(second).join();

        assertThat(store.list().join())
                .containsExactlyInAnyOrder(
                        first,
                        second
                );
    }

    @Test
    void listReturnsEmptyWhenStoreIsEmpty() {
        assertThat(store.list().join())
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    // create
    // ---------------------------------------------------------------------

    @Test
    void createStoresItem() {
        TestItem item =
                item("1", "Jack", "jack@example.com");

        assertThat(store.create(item).join())
                .isEqualTo(item);

        assertThat(store.get("1").join())
                .contains(item);
    }

    @Test
    void createRejectsNullItem() {
        assertThatThrownBy(() -> store.create(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("item must not be null");
    }

    @Test
    void createRejectsDuplicateId() {
        TestItem first =
                item("1", "First", "first@example.com");

        TestItem duplicate =
                item("1", "Second", "second@example.com");

        store.create(first).join();

        assertThatThrownBy(() ->
                store.create(duplicate).join()
        ).isInstanceOf(CompletionException.class);

        assertThat(store.get("1").join())
                .contains(first);
    }

    // ---------------------------------------------------------------------
    // unique constraints
    // ---------------------------------------------------------------------

    @Test
    void createEnforcesUniqueConstraint() {
        TestItem first =
                item("1", "First", "same@example.com");

        TestItem second =
                item("2", "Second", "same@example.com");

        store.create(first).join();

        assertThatThrownBy(() ->
                store.create(second).join()
        ).isInstanceOf(CompletionException.class);

        assertThat(store.get("1").join())
                .contains(first);

        assertThat(store.get("2").join())
                .isEmpty();
    }

    @Test
    void failedCreateDoesNotLeaveItemBehind() {
        TestItem first =
                item("1", "First", "same@example.com");

        TestItem second =
                item("2", "Second", "same@example.com");

        store.create(first).join();

        assertThatThrownBy(() ->
                store.create(second).join()
        ).isInstanceOf(CompletionException.class);

        assertThat(store.get("2").join())
                .isEmpty();
    }

    @Test
    void failedCreateDoesNotReleaseExistingUniqueLock() {
        TestItem first =
                item("1", "First", "same@example.com");

        TestItem second =
                item("2", "Second", "same@example.com");

        TestItem third =
                item("3", "Third", "same@example.com");

        store.create(first).join();

        assertThatThrownBy(() ->
                store.create(second).join()
        ).isInstanceOf(CompletionException.class);

        assertThatThrownBy(() ->
                store.create(third).join()
        ).isInstanceOf(CompletionException.class);
    }

    // ---------------------------------------------------------------------
    // update
    // ---------------------------------------------------------------------

    @Test
    void updateChangesItem() {
        TestItem original =
                item("1", "Old", "old@example.com");

        TestItem updated =
                item("1", "New", "old@example.com");

        store.create(original).join();

        assertThat(
                store.update(original, updated).join()
        ).isEqualTo(updated);

        assertThat(store.get("1").join())
                .contains(updated);
    }

    @Test
    void updateRejectsNullPrevious() {
        TestItem updated =
                item("1", "New", "new@example.com");

        assertThatThrownBy(() ->
                store.update(null, updated)
        ).isInstanceOf(NullPointerException.class)
                .hasMessage("previous must not be null");
    }

    @Test
    void updateRejectsNullUpdated() {
        TestItem previous =
                item("1", "Old", "old@example.com");

        assertThatThrownBy(() ->
                store.update(previous, null)
        ).isInstanceOf(NullPointerException.class)
                .hasMessage("updated must not be null");
    }

    @Test
    void updateRejectsIdChange() {
        TestItem original =
                item("1", "Original", "old@example.com");

        TestItem updated =
                item("2", "Updated", "new@example.com");

        store.create(original).join();

        assertThatThrownBy(() ->
                store.update(original, updated)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Item id cannot be changed during update"
                );

        assertThat(store.get("1").join())
                .contains(original);

        assertThat(store.get("2").join())
                .isEmpty();
    }

    @Test
    void updateChangesUniqueValue() {
        TestItem original =
                item("1", "Jack", "old@example.com");

        TestItem updated =
                item("1", "Jack", "new@example.com");

        store.create(original).join();

        store.update(original, updated).join();

        assertThat(store.get("1").join())
                .contains(updated);

        /*
         * The old value should have been released.
         */
        TestItem another =
                item("2", "Other", "old@example.com");

        assertThat(store.create(another).join())
                .isEqualTo(another);

        /*
         * The new value should still be reserved.
         */
        assertThatThrownBy(() ->
                store.create(
                        item(
                                "3",
                                "Third",
                                "new@example.com"
                        )
                ).join()
        ).isInstanceOf(CompletionException.class);
    }

    @Test
    void failedUniqueUpdateIsAtomic() {
        TestItem first =
                item("1", "First", "first@example.com");

        TestItem second =
                item("2", "Second", "second@example.com");

        store.create(first).join();
        store.create(second).join();

        TestItem attempted =
                item(
                        "1",
                        "Updated",
                        "second@example.com"
                );

        assertThatThrownBy(() ->
                store.update(first, attempted).join()
        ).isInstanceOf(CompletionException.class);

        /*
         * Item 1 must remain unchanged.
         */
        assertThat(store.get("1").join())
                .contains(first);

        /*
         * Item 2 must remain unchanged.
         */
        assertThat(store.get("2").join())
                .contains(second);

        /*
         * first@example.com must still be locked.
         */
        assertThatThrownBy(() ->
                store.create(
                        item(
                                "3",
                                "Third",
                                "first@example.com"
                        )
                ).join()
        ).isInstanceOf(CompletionException.class);

        /*
         * second@example.com must still be locked.
         */
        assertThatThrownBy(() ->
                store.create(
                        item(
                                "4",
                                "Fourth",
                                "second@example.com"
                        )
                ).join()
        ).isInstanceOf(CompletionException.class);
    }

    // ---------------------------------------------------------------------
    // delete
    // ---------------------------------------------------------------------

    @Test
    void deleteRemovesItem() {
        TestItem item =
                item("1", "Jack", "jack@example.com");

        store.create(item).join();

        store.delete(item).join();

        assertThat(store.get("1").join())
                .isEmpty();
    }

    @Test
    void deleteReleasesUniqueValue() {
        TestItem original =
                item("1", "Jack", "jack@example.com");

        store.create(original).join();

        store.delete(original).join();

        /*
         * Same unique value should now be available.
         */
        TestItem replacement =
                item("2", "Other", "jack@example.com");

        assertThat(store.create(replacement).join())
                .isEqualTo(replacement);
    }

    @Test
    void deleteRejectsNullItem() {
        assertThatThrownBy(() ->
                store.delete(null)
        ).isInstanceOf(NullPointerException.class)
                .hasMessage("item must not be null");
    }

    // ---------------------------------------------------------------------
    // GSI
    // ---------------------------------------------------------------------

    @Test
    void queryByIndexReturnsMatchingItems() {
        TestItem first =
                item("1", "Same Name", "first@example.com");

        TestItem second =
                item("2", "Same Name", "second@example.com");

        TestItem third =
                item("3", "Other Name", "third@example.com");

        store.create(first).join();
        store.create(second).join();
        store.create(third).join();

        assertThat(
                store.findByName("Same Name").join()
        ).containsExactlyInAnyOrder(
                first,
                second
        );
    }

    @Test
    void queryByIndexReturnsEmptyForNoMatches() {
        assertThat(
                store.findByEmail("missing@example.com").join()
        ).isEmpty();
    }

    // ---------------------------------------------------------------------
    // concurrency
    // ---------------------------------------------------------------------

    @Test
    void concurrentCreatesCannotUseSameUniqueValue() {
        TestItem first =
                item("1", "First", "same@example.com");

        TestItem second =
                item("2", "Second", "same@example.com");

        CompletableFuture<TestItem> firstFuture =
                store.create(first);

        CompletableFuture<TestItem> secondFuture =
                store.create(second);

        boolean firstSucceeded = completesSuccessfully(firstFuture);
        boolean secondSucceeded = completesSuccessfully(secondFuture);

        /*
         * Exactly one transaction can acquire the unique lock.
         */
        assertThat(firstSucceeded ^ secondSucceeded)
                .isTrue();

        int existingItems = 0;

        if (store.get("1").join().isPresent()) {
            existingItems++;
        }

        if (store.get("2").join().isPresent()) {
            existingItems++;
        }

        assertThat(existingItems)
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private boolean completesSuccessfully(
            CompletableFuture<?> future
    ) {
        try {
            future.join();
            return true;
        } catch (CompletionException e) {
            return false;
        }
    }

    private TestItem item(
            String id,
            String name,
            String email
    ) {
        return new TestItem(id, name, email);
    }

    private DynamoDbStoreDefinition<TestItem> definition() {
        TableSchema<TestItem> schema =
                TableSchema.fromBean(TestItem.class);

        return new DynamoDbStoreDefinition<>(
                TABLE_NAME,
                schema,
                TestItem::getId,
                List.of(
                        new UniqueConstraint<>(
                                "email",
                                TestItem::getEmail
                        )
                ),
                List.of(
                        new GlobalSecondaryIndexDefinition(
                                "email-index",
                                ProjectionType.ALL
                        ),
                        new GlobalSecondaryIndexDefinition(
                                "name-index",
                                ProjectionType.ALL
                        )
                )
        );
    }

    private void clearTable() {
        Map<String, AttributeValue> exclusiveStartKey = null;

        do {
            var requestBuilder = software.amazon.awssdk.services.dynamodb.model.ScanRequest
                    .builder()
                    .tableName(TABLE_NAME);

            if (exclusiveStartKey != null) {
                requestBuilder.exclusiveStartKey(exclusiveStartKey);
            }

            var response = client.scan(requestBuilder.build()).join();

            for (Map<String, AttributeValue> item : response.items()) {
                client.deleteItem(request ->
                        request
                                .tableName(TABLE_NAME)
                                .key(Map.of(
                                        "id",
                                        item.get("id")
                                ))
                ).join();
            }

            exclusiveStartKey = response.lastEvaluatedKey();

        } while (exclusiveStartKey != null
                && !exclusiveStartKey.isEmpty());
    }

    private static final class TestStore
            extends DynamoDbStore<TestItem> {

        private TestStore(
                DynamoDbAsyncClient client,
                DynamoDbStoreDefinition<TestItem> definition
        ) {
            super(client, definition);
        }

        protected CompletableFuture<Void> initialize() {
            return super.initialize();
        }

        private CompletableFuture<List<TestItem>> findByEmail(
                String email
        ) {
            return queryByIndex(
                    "email-index",
                    email
            );
        }

        private CompletableFuture<List<TestItem>> findByName(
                String name
        ) {
            return queryByIndex(
                    "name-index",
                    name
            );
        }
    }

    @DynamoDbBean
    public static class TestItem {

        private String id;
        private String name;
        private String email;

        public TestItem() {
        }

        public TestItem(
                String id,
                String name,
                String email
        ) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        @DynamoDbPartitionKey
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        @DynamoDbSecondaryPartitionKey(indexNames = "name-index")
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @DynamoDbSecondaryPartitionKey(
                indexNames = "email-index"
        )
        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof TestItem other)) {
                return false;
            }

            return java.util.Objects.equals(id, other.id)
                    && java.util.Objects.equals(name, other.name)
                    && java.util.Objects.equals(email, other.email);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    id,
                    name,
                    email
            );
        }
    }
}