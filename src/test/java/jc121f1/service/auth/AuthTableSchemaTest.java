package jc121f1.service.auth;

import jc121f1.model.auth.dao.Credential;
import jc121f1.model.auth.dao.Session;
import jc121f1.model.auth.dao.User;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

class AuthTableSchemaTest {
    @Test
    void auth_records_round_trip_through_dynamo_db_without_scopes() {
        assertRoundTrip(User.class, User.builder()
                .userId("u-1").accountId("a-1").email("user@example.com").build());
        assertRoundTrip(Credential.class, Credential.builder()
                .credentialId("cre-1").accountId("a-1").createdByUserId("u-1").secretHash("hash").build());
        assertRoundTrip(Session.class, Session.builder()
                .token("token").accountId("a-1").subjectId("u-1")
                .subjectType(Session.SubjectType.USER).build());
    }

    @Test
    void legacy_credentials_have_no_inferred_creator() {
        TableSchema<Credential> schema = TableSchema.fromImmutableClass(Credential.class);
        var attributes = schema.itemToMap(Credential.builder()
                .credentialId("cre-old").accountId("a-1").secretHash("hash").build(), true);
        Assertions.assertThat(attributes).doesNotContainKey("createdByUserId");
        Assertions.assertThat(schema.mapToItem(attributes).createdByUserId()).isNull();
    }

    private <T> void assertRoundTrip(Class<T> type, T item) {
        TableSchema<T> schema = TableSchema.fromImmutableClass(type);
        var attributes = schema.itemToMap(item, true);
        Assertions.assertThat(schema.mapToItem(attributes)).isEqualTo(item);
    }
}
