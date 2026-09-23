package jc121f1.services.auth.store;

import jc121f1.common.store.GenericStore;
import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.User;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public interface AccountStore extends GenericStore<Account> {
    /** Atomically replace the observed active owner only while the proposed user still exists in this account. */
    CompletableFuture<Account> transferOwnership(Account observed, User proposedOwner, Instant updatedAt);

    /** Atomically delete the user only while the account exists and that user is not its current owner. */
    CompletableFuture<Void> deleteUserIfNotOwner(User user);
}
