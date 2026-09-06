package jc121f1.services.auth.store;

import jc121f1.common.store.GenericStore;
import jc121f1.model.auth.dao.User;

import java.util.concurrent.CompletableFuture;

public interface UserStore extends GenericStore<User> {
    CompletableFuture<User> findByEmail(String email);
}
