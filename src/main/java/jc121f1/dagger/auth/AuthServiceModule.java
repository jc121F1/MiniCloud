package jc121f1.dagger.auth;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import jc121f1.services.auth.AuthService;
import jc121f1.services.auth.AuthServiceImpl;
import jc121f1.services.auth.store.AccountStore;
import jc121f1.services.auth.store.CredentialStore;
import jc121f1.services.auth.store.SessionStore;
import jc121f1.services.auth.store.UserStore;
import jc121f1.services.auth.store.nosql.DynamoDbAccountStore;
import jc121f1.services.auth.store.nosql.DynamoDbCredentialStore;
import jc121f1.services.auth.store.nosql.DynamoDbSessionStore;
import jc121f1.services.auth.store.nosql.DynamoDbUserStore;

import javax.inject.Singleton;
import java.security.SecureRandom;

@Module
public abstract class AuthServiceModule {
    @Binds @Singleton
    public abstract AuthService authService(AuthServiceImpl authService);

    @Binds @Singleton
    public abstract AccountStore accountStore(DynamoDbAccountStore accountStore);

    @Binds @Singleton
    public abstract UserStore userStore(DynamoDbUserStore userStore);

    @Binds @Singleton
    public abstract SessionStore sessionStore(DynamoDbSessionStore sessionStore);

    @Binds @Singleton
    public abstract CredentialStore credentialStore(DynamoDbCredentialStore credentialStore);

    @Provides @Singleton
    public static SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
