package jc121f1.dagger;

import dagger.Binds;
import dagger.Module;
import jc121f1.services.authz.store.PolicyStore;
import jc121f1.services.authz.store.nosql.DynamoDbPolicyStore;

import javax.inject.Singleton;

@Module
public abstract class AuthorizationPersistenceModule {
    @Binds
    @Singleton
    public abstract PolicyStore policyStore(DynamoDbPolicyStore store);
}
