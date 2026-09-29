package jc121f1.dagger;

import dagger.Binds;
import dagger.Module;
import jc121f1.services.authz.store.PolicyStore;
import jc121f1.services.authz.store.nosql.DynamoDbPolicyStore;

@Module
public abstract class AuthorizationPersistenceModule {
    @Binds
    public abstract PolicyStore policyStore(DynamoDbPolicyStore store);
}
