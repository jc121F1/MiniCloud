package jc121f1.common.store.nosql;

import jc121f1.annotations.MiniCloudTest;
import jc121f1.model.instance.dao.Instance;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.List;

@MiniCloudTest
public class DynamoDbStoreDefinitionTest {

    private final static String TABLE_NAME = "TABLE_NAME";

    @Nested
    class Given_a_dynamo_db_store_definition {

        @Nested class When_extracting_id {
            DynamoDbStoreDefinition<Instance> definition;
            @BeforeEach
            void setup() {
                definition = new DynamoDbStoreDefinition<>(
                        TABLE_NAME,
                        TableSchema.fromImmutableClass(Instance.class),
                        Instance::id,
                        List.of(),
                        List.of()
                );
            }

            @Test
            void It_should_require_item_not_be_null() {
                Assertions.assertThrows(NullPointerException.class, () -> {
                    definition.extractId(null);
                });
            }

            @Test
            void It_should_require_id_not_be_null() {
                Assertions.assertThrows(IllegalArgumentException.class, () -> {
                    definition.extractId(Instance.builder().id(null).build());
                });
            }

            @Test
            void It_should_require_id_not_be_blank() {
                Assertions.assertThrows(IllegalArgumentException.class, () -> {
                    definition.extractId(Instance.builder().id("").build());
                });
            }

            @Test
            void It_should_return_valid_identifier() {
                Instance instance = Instance.builder().id("IDENTIFIER").build();
                String extracted = definition.extractId(instance);
                Assertions.assertEquals("IDENTIFIER", extracted);
            }
        }
    }

    @Nested class When_constructing_dynamo_db_store_definition {
        @Nested class It_should_enforce_non_null {
            @Test
            void It_should_require_table_name_not_be_null_or_blank() {
                Assertions.assertThrows(NullPointerException.class, () -> {
                    new DynamoDbStoreDefinition<>(
                            null,
                            TableSchema.fromImmutableClass(Instance.class),
                            Instance::id,
                            List.of(),
                            List.of()
                    );
                });

                Assertions.assertThrows(IllegalArgumentException.class, () -> {
                    new DynamoDbStoreDefinition<>(
                            "",
                            TableSchema.fromImmutableClass(Instance.class),
                            Instance::id,
                            List.of(),
                            List.of()
                    );
                });
            }

            @Test
            void It_should_require_table_schema_not_be_null() {
                Assertions.assertThrows(NullPointerException.class, () -> {
                    new DynamoDbStoreDefinition<>(
                            TABLE_NAME,
                            null,
                            Instance::id,
                            List.of(),
                            List.of()
                    );
                });
            }

            @Test
            void It_should_require_idExtractor_not_be_null() {
                Assertions.assertThrows(NullPointerException.class, () -> {
                    new DynamoDbStoreDefinition<>(
                            TABLE_NAME,
                            TableSchema.fromImmutableClass(Instance.class),
                            null,
                            List.of(),
                            List.of()
                    );
                });
            }

            @Test
            void It_should_require_unique_constraints_not_be_null() {
                Assertions.assertThrows(NullPointerException.class, () -> {
                    new DynamoDbStoreDefinition<>(
                            TABLE_NAME,
                            TableSchema.fromImmutableClass(Instance.class),
                            Instance::id,
                            null,
                            List.of()
                    );
                });
            }

            @Test
            void It_should_require_gsi_not_be_null() {
                Assertions.assertThrows(NullPointerException.class, () -> {
                    new DynamoDbStoreDefinition<>(
                            TABLE_NAME,
                            TableSchema.fromImmutableClass(Instance.class),
                            Instance::id,
                            List.of(),
                            null
                    );
                });
            }
        }
        @Nested class It_should_enforce_max_unique_constraints {
            @Test void It_should_reject_100_constraints() {
                UniqueConstraint<Instance>[] constraints = new UniqueConstraint[100];

                for (int i = 0; i < constraints.length; i++) {
                    constraints[i] = new UniqueConstraint<>("id", ((Instance::id)));
                }

                Assertions.assertThrows(IllegalArgumentException.class, () -> {
                    new DynamoDbStoreDefinition<>(
                            TABLE_NAME,
                            TableSchema.fromImmutableClass(Instance.class),
                            Instance::id,
                            List.of(constraints),
                            List.of()
                    );
                });
            }
        }
    }
}
