package jc121f1.runtime;

import jc121f1.common.store.nosql.DynamoDbStore;
import jc121f1.services.auth.store.nosql.DynamoDbAccountStore;
import jc121f1.services.auth.store.nosql.DynamoDbCredentialStore;
import jc121f1.services.auth.store.nosql.DynamoDbSessionStore;
import jc121f1.services.auth.store.nosql.DynamoDbUserStore;
import jc121f1.services.authz.store.nosql.DynamoDbPolicyStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/** Initializes the same scoped stores used by service bindings, before HTTP opens. */
@Singleton
public final class StoreInitializer {
    private final List<DynamoDbStore<?>> stores;
    private boolean initialized;

    @Inject
    public StoreInitializer(DynamoDbUserStore users, DynamoDbAccountStore accounts,
                            DynamoDbCredentialStore credentials, DynamoDbSessionStore sessions,
                            DynamoDbPolicyStore policies) {
        stores = List.of(users, accounts, credentials, sessions, policies);
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        for (DynamoDbStore<?> store : stores) {
            store.initialize().join();
        }
        initialized = true;
    }
}
