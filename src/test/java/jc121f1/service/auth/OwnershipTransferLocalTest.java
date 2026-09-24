package jc121f1.service.auth;

import jc121f1.model.auth.dao.Account;
import jc121f1.model.auth.dao.User;
import jc121f1.services.auth.store.nosql.DynamoDbAccountStore;
import jc121f1.services.auth.store.nosql.DynamoDbUserStore;
import jc121f1.services.authz.exceptions.PolicyConflictException;
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

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DynamoDbLocalAvailable", matches = "True")
class OwnershipTransferLocalTest {
    private final String accountTable = "OwnershipAccounts-" + UUID.randomUUID();
    private final String userTable = "OwnershipUsers-" + UUID.randomUUID();
    private DynamoDbAsyncClient client;
    private DynamoDbAccountStore accounts;
    private DynamoDbUserStore users;

    @BeforeAll
    void initialize() {
        client = DynamoDbAsyncClient.builder().endpointOverride(URI.create("http://localhost:8000"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy"))).build();
        users = new DynamoDbUserStore(client, userTable);
        accounts = new DynamoDbAccountStore(client, users, accountTable);
    }

    @AfterAll
    void cleanup() {
        if (client != null) {
            try {
                client.deleteTable(request -> request.tableName(accountTable)).join();
                client.deleteTable(request -> request.tableName(userTable)).join();
            } finally {
                client.close();
            }
        }
    }

    @Test
    void competing_transfers_and_owner_deletion_are_atomic() {
        Fixture fixture = fixture();
        CompletableFuture<Account> first = accounts.transferOwnership(fixture.account, fixture.second, Instant.now());
        CompletableFuture<Account> second = accounts.transferOwnership(fixture.account, fixture.third, Instant.now());
        int successes = 0;
        for (CompletableFuture<Account> write : List.of(first, second)) {
            try {
                write.join();
                successes++;
            } catch (RuntimeException error) {
                Assertions.assertThat(error).hasCauseInstanceOf(PolicyConflictException.class);
            }
        }
        Assertions.assertThat(successes).isEqualTo(1);
        String winner = accounts.get(fixture.account.accountId(), true).join().orElseThrow().ownerId();
        Assertions.assertThat(winner).isIn(fixture.second.userId(), fixture.third.userId());
        User current = winner.equals(fixture.second.userId()) ? fixture.second : fixture.third;
        Assertions.assertThatThrownBy(() -> accounts.deleteUserIfNotOwner(current).join())
                .hasCauseInstanceOf(PolicyConflictException.class);
        Assertions.assertThat(users.get(current.userId(), true).join()).isPresent();
        accounts.deleteUserIfNotOwner(fixture.first).join();
        Assertions.assertThat(users.get(fixture.first.userId(), true).join()).isEmpty();
    }

    @Test
    void deleting_proposed_owner_before_transfer_conflicts_without_changing_owner() {
        Fixture fixture = fixture();
        accounts.deleteUserIfNotOwner(fixture.second).join();
        Assertions.assertThatThrownBy(() -> accounts.transferOwnership(fixture.account, fixture.second, Instant.now()).join())
                .hasCauseInstanceOf(PolicyConflictException.class);
        Assertions.assertThat(accounts.get(fixture.account.accountId(), true).join().orElseThrow().ownerId())
                .isEqualTo(fixture.first.userId());
    }

    @Test
    void transfer_and_proposed_owner_deletion_cannot_both_commit() {
        Fixture fixture = fixture();
        CompletableFuture<Account> transfer = accounts.transferOwnership(fixture.account, fixture.second, Instant.now());
        CompletableFuture<Void> deletion = accounts.deleteUserIfNotOwner(fixture.second);
        try {
            transfer.join();
        } catch (RuntimeException error) {
            Assertions.assertThat(error).hasCauseInstanceOf(PolicyConflictException.class);
        }
        try {
            deletion.join();
        } catch (RuntimeException error) {
            Assertions.assertThat(error).hasCauseInstanceOf(PolicyConflictException.class);
        }
        String ownerId = accounts.get(fixture.account.accountId(), true).join().orElseThrow().ownerId();
        Assertions.assertThat(users.get(ownerId, true).join()).isPresent();
        Assertions.assertThat(ownerId.equals(fixture.second.userId())
                && users.get(fixture.second.userId(), true).join().isEmpty()).isFalse();
    }

    private Fixture fixture() {
        String accountId = "a-" + UUID.randomUUID();
        User first = user(accountId);
        User second = user(accountId);
        User third = user(accountId);
        Account account = Account.builder().accountId(accountId).ownerId(first.userId())
                .name(accountId).status(Account.AccountStatus.ACTIVE).createdAt(Instant.now()).build();
        accounts.create(account).join();
        users.create(first).join();
        users.create(second).join();
        users.create(third).join();
        return new Fixture(account, first, second, third);
    }

    private static User user(String accountId) {
        String id = "u-" + UUID.randomUUID();
        return User.builder().userId(id).accountId(accountId).email(id + "@example.test").build();
    }

    private record Fixture(Account account, User first, User second, User third) { }
}
