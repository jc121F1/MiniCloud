package jc121f1.runtime;

import jc121f1.services.auth.store.nosql.DynamoDbAccountStore;
import jc121f1.services.auth.store.nosql.DynamoDbCredentialStore;
import jc121f1.services.auth.store.nosql.DynamoDbSessionStore;
import jc121f1.services.auth.store.nosql.DynamoDbUserStore;
import jc121f1.services.authz.store.nosql.DynamoDbPolicyStore;
import jc121f1.services.instance.store.nosql.DynamoDbInstanceStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.util.concurrent.CompletableFuture;

class StoreInitializerTest {
    private final DynamoDbUserStore users = Mockito.mock(DynamoDbUserStore.class);
    private final DynamoDbAccountStore accounts = Mockito.mock(DynamoDbAccountStore.class);
    private final DynamoDbCredentialStore credentials = Mockito.mock(DynamoDbCredentialStore.class);
    private final DynamoDbSessionStore sessions = Mockito.mock(DynamoDbSessionStore.class);
    private final DynamoDbPolicyStore policies = Mockito.mock(DynamoDbPolicyStore.class);
    private final StoreInitializer initializer = new StoreInitializer(users, accounts, credentials, sessions, policies);

    @Test
    void constructorsDoNotAccessDynamoDb() {
        DynamoDbAsyncClient client = Mockito.mock(DynamoDbAsyncClient.class);
        DynamoDbUserStore userStore = new DynamoDbUserStore(client, "users-test");
        new DynamoDbAccountStore(client, userStore, "accounts-test");
        new DynamoDbCredentialStore(client, "credentials-test");
        new DynamoDbSessionStore(client, "sessions-test");
        new DynamoDbPolicyStore(client, "policies-test");
        new DynamoDbInstanceStore(client, "instances-test");
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void initializesScopedStoresInDependencyOrderOnlyOnce() {
        successfulStores();
        initializer.initialize();
        initializer.initialize();
        InOrder order = Mockito.inOrder(users, accounts, credentials, sessions, policies);
        order.verify(users).initialize();
        order.verify(accounts).initialize();
        order.verify(credentials).initialize();
        order.verify(sessions).initialize();
        order.verify(policies).initialize();
        order.verifyNoMoreInteractions();
    }

    @Test
    void failureStopsInitializationBeforeLaterStores() {
        IllegalStateException failure = new IllegalStateException("table unavailable");
        Mockito.when(users.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(accounts.initialize()).thenReturn(CompletableFuture.failedFuture(failure));
        Assertions.assertThatThrownBy(initializer::initialize).hasRootCauseMessage("table unavailable");
        Mockito.verifyNoInteractions(credentials, sessions, policies);
    }

    private void successfulStores() {
        Mockito.when(users.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(accounts.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(credentials.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(sessions.initialize()).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(policies.initialize()).thenReturn(CompletableFuture.completedFuture(null));
    }
}
