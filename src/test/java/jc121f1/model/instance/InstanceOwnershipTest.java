package jc121f1.model.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jc121f1.model.instance.dao.Instance;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

class InstanceOwnershipTest {
    @Test
    void ownerIsPersistedAndSurvivesStateCopies() {
        Instance instance = Instance.builder()
                .id("i-123")
                .name("example")
                .accountId("a-123")
                .state(InstanceState.STARTING)
                .build();

        TableSchema<Instance> schema = TableSchema.fromImmutableClass(Instance.class);
        Instance restored = schema.mapToItem(schema.itemToMap(instance, true));

        Assertions.assertThat(restored.accountId()).isEqualTo("a-123");
        Assertions.assertThat(restored.toBuilder().state(InstanceState.RUNNING).build().accountId())
                .isEqualTo("a-123");
    }

    @Test
    void clientJsonCannotSupplyOrReadOwner() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Instance instance = mapper.readValue("""
                {"id":"i-123","name":"example","accountId":"a-spoofed","state":"RUNNING"}
                """, Instance.class);

        Assertions.assertThat(instance.accountId()).isNull();
        Assertions.assertThat(mapper.writeValueAsString(
                instance.toBuilder().accountId("a-123").build()))
                .doesNotContain("accountId", "a-123");
    }
}
