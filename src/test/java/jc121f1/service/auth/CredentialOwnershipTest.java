package jc121f1.service.auth;

import jc121f1.model.auth.dao.Credential;
import jc121f1.services.auth.store.nosql.DynamoDbCredentialStore;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.util.List;

class CredentialOwnershipTest {
    @Test
    @SuppressWarnings("unchecked")
    void rejects_reparenting_before_accessing_storage() {
        DynamoDbAsyncClient client = Mockito.mock(DynamoDbAsyncClient.class);
        DynamoDbAsyncTable<Credential> table = Mockito.mock(DynamoDbAsyncTable.class);
        DynamoDbCredentialStore store = new DynamoDbCredentialStore(client, table);
        Credential original = Credential.builder().credentialId("cre-1").accountId("a-1")
                .createdByUserId("u-1").build();
        for (Credential updated : List.of(original.toBuilder().createdByUserId("u-2").build(),
                original.toBuilder().createdByUserId(null).build(), original.toBuilder().accountId("a-2").build())) {
            Assertions.assertThatThrownBy(() -> store.update(original, updated))
                    .isInstanceOf(IllegalArgumentException.class);
            Assertions.assertThatThrownBy(() -> store.update(original, updated,
                    Expression.builder().expression("attribute_exists(credentialId)").build()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        Credential legacy = original.toBuilder().createdByUserId(null).build();
        Assertions.assertThatThrownBy(() -> store.update(legacy, original)).isInstanceOf(IllegalArgumentException.class);
        Mockito.verifyNoInteractions(client, table);
    }
}
