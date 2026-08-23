package jc121f1.service.store.nosql;

import jc121f1.model.instance.dao.Instance;
import jc121f1.services.instance.store.nosql.DynamoDbInstanceStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

@ExtendWith(MockitoExtension.class)
class DynamoDbInstanceStoreTest {

    private static final String INSTANCE_ID = "instance-123";
    private static final String INSTANCE_NAME = "test-instance";
    private static final String NAME_LOCK_ID =
            "__name_lock__" + INSTANCE_NAME;

    @Mock
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Mock
    private DynamoDbAsyncTable<Instance> table;

    @Mock
    private DynamoDbAsyncIndex<Instance> index;

    @Mock
    private SdkPublisher<Page<Instance>> publisher;

    @Mock
    private SdkPublisher<Instance> instancePublisher;

    @Mock
    private Instance instance;

    private DynamoDbInstanceStore store;

    @BeforeEach
    void setUp() {
        store = new DynamoDbInstanceStore(
                dynamoDbAsyncClient,
                table
        );
    }

    @Nested
    class Get {

        @Test
        void returnsInstanceWhenPresent() {
            Mockito.when(table.getItem(
                    ArgumentMatchers.any(Consumer.class)
            )).thenReturn(
                    CompletableFuture.completedFuture(instance)
            );

            Optional<Instance> result =
                    store.get(INSTANCE_ID).join();

            Assertions.assertEquals(
                    Optional.of(instance),
                    result
            );

            Mockito.verify(table).getItem(
                    ArgumentMatchers.any(Consumer.class)
            );
        }

        @Test
        void returnsEmptyWhenAbsent() {
            Mockito.when(table.getItem(
                    ArgumentMatchers.any(Consumer.class)
            )).thenReturn(
                    CompletableFuture.completedFuture(null)
            );

            Optional<Instance> result =
                    store.get(INSTANCE_ID).join();

            Assertions.assertEquals(
                    Optional.empty(),
                    result
            );
        }

        @Test
        void propagatesFailure() {
            RuntimeException exception =
                    new RuntimeException("DynamoDB failure");

            Mockito.when(table.getItem(
                    ArgumentMatchers.any(Consumer.class)
            )).thenReturn(
                    CompletableFuture.failedFuture(exception)
            );

            Assertions.assertThrows(
                    RuntimeException.class,
                    () -> store.get(INSTANCE_ID).join()
            );
        }
    }
    @Nested
    class GetByName {

        @Test
        void returnsInstanceWhenExactlyOneInstanceExists() {
            String name = "instance-1";
            Instance instance = Instance.builder()
                    .name(name)
                    .build();

            mockQuery(Page.create(List.of(instance)));

            Optional<Instance> result = store.getByName(name).join();

            Assertions.assertTrue(result.isPresent());
            Assertions.assertEquals(instance, result.get());
        }

        @Test
        void returnsEmptyWhenNoInstancesExist() {
            String name = "instance-1";

            mockQuery(Page.create(List.of()));

            Optional<Instance> result = store.getByName(name).join();

            Assertions.assertTrue(result.isEmpty());
        }

        @Test
        void throwsWhenMoreThanOneInstanceExists() {
            String name = "instance-1";

            Instance first = Instance.builder()
                    .name(name)
                    .build();

            Instance second = Instance.builder()
                    .name(name)
                    .build();

            mockQuery(Page.create(List.of(first, second)));

            CompletionException exception = Assertions.assertThrows(
                    CompletionException.class,
                    () -> store.getByName(name).join()
            );

            Assertions.assertInstanceOf(
                    IllegalStateException.class,
                    exception.getCause()
            );

            Assertions.assertEquals(
                    "More than one instance returned from get by name!",
                    exception.getCause().getMessage()
            );
        }
    }

    private void mockQuery(Page<Instance> page) {
        Mockito.when(table.index(DynamoDbInstanceStore.INSTANCE_GSI))
                .thenReturn(index);

        Mockito.when(index.query(
                Mockito.<Consumer<QueryEnhancedRequest.Builder>>any()
        )).thenReturn(publisher);

        Mockito.when(publisher.flatMapIterable(
                Mockito.<java.util.function.Function<Page<Instance>, Iterable<Instance>>>any()
        )).thenReturn(instancePublisher);

        Mockito.doAnswer(invocation -> {
            Consumer<Instance> consumer = invocation.getArgument(0);

            for (Instance item : page.items()) {
                consumer.accept(item);
            }

            return CompletableFuture.completedFuture(null);
        }).when(instancePublisher).subscribe(
                Mockito.<Consumer<Instance>>any()
        );
    }

    @Nested
    class Create {

        @BeforeEach
        void setUpInstance() {
            Mockito.when(instance.getId())
                    .thenReturn(INSTANCE_ID);

            Mockito.when(instance.getName())
                    .thenReturn(INSTANCE_NAME);
        }

        @Test
        void createsInstanceAndNameLock() {
            Mockito.when(dynamoDbAsyncClient.transactWriteItems(
                    ArgumentMatchers.any(TransactWriteItemsRequest.class)
            )).thenReturn(
                    CompletableFuture.completedFuture(null)
            );

            Instance result = store.create(instance).join();

            Assertions.assertEquals(
                    instance,
                    result
            );

            ArgumentCaptor<TransactWriteItemsRequest> captor =
                    ArgumentCaptor.forClass(
                            TransactWriteItemsRequest.class
                    );

            Mockito.verify(dynamoDbAsyncClient)
                    .transactWriteItems(captor.capture());

            TransactWriteItemsRequest request = captor.getValue();

            Assertions.assertEquals(
                    2,
                    request.transactItems().size()
            );

            TransactWriteItem instanceWrite =
                    request.transactItems().get(0);

            TransactWriteItem nameLockWrite =
                    request.transactItems().get(1);

            Assertions.assertNotNull(instanceWrite.put());
            Assertions.assertNotNull(nameLockWrite.put());

            Assertions.assertEquals(
                    "MiniCloudInstanceStore",
                    instanceWrite.put().tableName()
            );

            Assertions.assertEquals(
                    "MiniCloudInstanceStore",
                    nameLockWrite.put().tableName()
            );

            Assertions.assertEquals(
                    INSTANCE_ID,
                    instanceWrite.put()
                            .item()
                            .get("id")
                            .s()
            );

            Assertions.assertEquals(
                    NAME_LOCK_ID,
                    nameLockWrite.put()
                            .item()
                            .get("id")
                            .s()
            );

            Assertions.assertEquals(
                    "attribute_not_exists(id)",
                    instanceWrite.put()
                            .conditionExpression()
            );

            Assertions.assertEquals(
                    "attribute_not_exists(id)",
                    nameLockWrite.put()
                            .conditionExpression()
            );

            Mockito.verifyNoInteractions(table);
        }

        @Test
        void propagatesFailure() {
            RuntimeException exception =
                    new RuntimeException("Create failed");

            Mockito.when(dynamoDbAsyncClient.transactWriteItems(
                    ArgumentMatchers.any(TransactWriteItemsRequest.class)
            )).thenReturn(
                    CompletableFuture.failedFuture(exception)
            );

            Assertions.assertThrows(
                    RuntimeException.class,
                    () -> store.create(instance).join()
            );

            Mockito.verify(dynamoDbAsyncClient)
                    .transactWriteItems(
                            ArgumentMatchers.any(
                                    TransactWriteItemsRequest.class
                            )
                    );
        }
    }

    @Nested
    class Update {

        private Instance previous;
        private Instance updated;

        @BeforeEach
        void setUpInstances() {
            previous = Instance.builder()
                    .id(INSTANCE_ID)
                    .name(INSTANCE_NAME)
                    .build();

            updated = Instance.builder()
                    .id(INSTANCE_ID)
                    .name(INSTANCE_NAME)
                    .build();
        }

        @Test
        void updatesInstanceWithoutChangingName() {
            Mockito.when(dynamoDbAsyncClient.transactWriteItems(
                    ArgumentMatchers.any(TransactWriteItemsRequest.class)
            )).thenReturn(
                    CompletableFuture.completedFuture(null)
            );

            Instance result = store.update(previous, updated).join();

            Assertions.assertEquals(
                    updated,
                    result
            );

            ArgumentCaptor<TransactWriteItemsRequest> captor =
                    ArgumentCaptor.forClass(
                            TransactWriteItemsRequest.class
                    );

            Mockito.verify(dynamoDbAsyncClient)
                    .transactWriteItems(captor.capture());

            TransactWriteItemsRequest request = captor.getValue();

            Assertions.assertEquals(
                    1,
                    request.transactItems().size()
            );

            TransactWriteItem instanceWrite =
                    request.transactItems().getFirst();

            Assertions.assertNotNull(instanceWrite.put());

            Assertions.assertEquals(
                    "MiniCloudInstanceStore",
                    instanceWrite.put().tableName()
            );

            Assertions.assertEquals(
                    INSTANCE_ID,
                    instanceWrite.put()
                            .item()
                            .get("id")
                            .s()
            );

            Assertions.assertEquals(
                    "attribute_exists(id) AND #name = :previousName",
                    instanceWrite.put()
                            .conditionExpression()
            );

            Assertions.assertEquals(
                    INSTANCE_NAME,
                    instanceWrite.put()
                            .expressionAttributeValues()
                            .get(":previousName")
                            .s()
            );

            Mockito.verifyNoInteractions(table);
        }

        @Test
        void updatesInstanceAndNameLockWhenNameChanges() {
            String newName = "renamed-instance";

            previous = Instance.builder()
                    .id(INSTANCE_ID)
                    .name(INSTANCE_NAME)
                    .build();

            updated = Instance.builder()
                    .id(INSTANCE_ID)
                    .name(newName)
                    .build();

            Mockito.when(dynamoDbAsyncClient.transactWriteItems(
                    ArgumentMatchers.any(TransactWriteItemsRequest.class)
            )).thenReturn(
                    CompletableFuture.completedFuture(null)
            );

            Instance result = store.update(previous, updated).join();

            Assertions.assertEquals(
                    updated,
                    result
            );

            ArgumentCaptor<TransactWriteItemsRequest> captor =
                    ArgumentCaptor.forClass(
                            TransactWriteItemsRequest.class
                    );

            Mockito.verify(dynamoDbAsyncClient)
                    .transactWriteItems(captor.capture());

            TransactWriteItemsRequest request = captor.getValue();

            Assertions.assertEquals(
                    3,
                    request.transactItems().size()
            );

            TransactWriteItem deleteLock =
                    request.transactItems().get(0);

            TransactWriteItem createLock =
                    request.transactItems().get(1);

            TransactWriteItem instanceWrite =
                    request.transactItems().get(2);

            Assertions.assertNotNull(deleteLock.delete());
            Assertions.assertNotNull(createLock.put());
            Assertions.assertNotNull(instanceWrite.put());

            Assertions.assertEquals(
                    NAME_LOCK_ID,
                    deleteLock.delete()
                            .key()
                            .get("id")
                            .s()
            );

            Assertions.assertEquals(
                    "__name_lock__" + newName,
                    createLock.put()
                            .item()
                            .get("id")
                            .s()
            );

            Assertions.assertEquals(
                    "attribute_not_exists(id)",
                    createLock.put()
                            .conditionExpression()
            );

            Assertions.assertEquals(
                    INSTANCE_ID,
                    instanceWrite.put()
                            .item()
                            .get("id")
                            .s()
            );

            Assertions.assertEquals(
                    "attribute_exists(id) AND #name = :previousName",
                    instanceWrite.put()
                            .conditionExpression()
            );

            Assertions.assertEquals(
                    INSTANCE_NAME,
                    instanceWrite.put()
                            .expressionAttributeValues()
                            .get(":previousName")
                            .s()
            );

            Mockito.verifyNoInteractions(table);
        }

        @Test
        void propagatesFailure() {
            RuntimeException exception =
                    new RuntimeException("Update failed");

            Mockito.when(dynamoDbAsyncClient.transactWriteItems(
                    ArgumentMatchers.any(TransactWriteItemsRequest.class)
            )).thenReturn(
                    CompletableFuture.failedFuture(exception)
            );

            Assertions.assertThrows(
                    RuntimeException.class,
                    () -> store.update(previous, updated).join()
            );

            Mockito.verify(dynamoDbAsyncClient)
                    .transactWriteItems(
                            ArgumentMatchers.any(
                                    TransactWriteItemsRequest.class
                            )
                    );
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesInstanceAndNameLock() {
            Instance instance = Instance.builder()
                    .id(INSTANCE_ID)
                    .name(INSTANCE_NAME)
                    .build();

            Mockito.when(dynamoDbAsyncClient.transactWriteItems(
                    ArgumentMatchers.any(TransactWriteItemsRequest.class)
            )).thenReturn(
                    CompletableFuture.completedFuture(null)
            );

            store.delete(instance).join();

            ArgumentCaptor<TransactWriteItemsRequest> captor =
                    ArgumentCaptor.forClass(
                            TransactWriteItemsRequest.class
                    );

            Mockito.verify(dynamoDbAsyncClient)
                    .transactWriteItems(captor.capture());

            TransactWriteItemsRequest request = captor.getValue();

            Assertions.assertEquals(
                    2,
                    request.transactItems().size()
            );

            TransactWriteItem instanceDelete =
                    request.transactItems().get(0);

            TransactWriteItem nameLockDelete =
                    request.transactItems().get(1);

            Assertions.assertNotNull(instanceDelete.delete());
            Assertions.assertNotNull(nameLockDelete.delete());

            Assertions.assertEquals(
                    INSTANCE_ID,
                    instanceDelete.delete()
                            .key()
                            .get("id")
                            .s()
            );

            Assertions.assertEquals(
                    NAME_LOCK_ID,
                    nameLockDelete.delete()
                            .key()
                            .get("id")
                            .s()
            );

            Assertions.assertEquals(
                    "MiniCloudInstanceStore",
                    instanceDelete.delete().tableName()
            );

            Assertions.assertEquals(
                    "MiniCloudInstanceStore",
                    nameLockDelete.delete().tableName()
            );

            Mockito.verifyNoInteractions(table);
        }

        @Test
        void propagatesFailure() {
            Instance instance = Instance.builder()
                    .id(INSTANCE_ID)
                    .name(INSTANCE_NAME)
                    .build();

            RuntimeException exception =
                    new RuntimeException("Delete failed");

            Mockito.when(dynamoDbAsyncClient.transactWriteItems(
                    ArgumentMatchers.any(TransactWriteItemsRequest.class)
            )).thenReturn(
                    CompletableFuture.failedFuture(exception)
            );

            Assertions.assertThrows(
                    RuntimeException.class,
                    () -> store.delete(instance).join()
            );

            Mockito.verify(dynamoDbAsyncClient)
                    .transactWriteItems(
                            ArgumentMatchers.any(
                                    TransactWriteItemsRequest.class
                            )
                    );
        }
    }
}