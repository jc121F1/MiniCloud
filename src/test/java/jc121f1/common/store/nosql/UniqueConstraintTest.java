package jc121f1.common.store.nosql;

import jc121f1.annotations.MiniCloudTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@MiniCloudTest
public class UniqueConstraintTest {

    private final static String TEST_NAME = "TEST_NAME";

    @Nested
    class Given_a_unique_constraint {
        UniqueConstraint<String> uniqueConstraint;

        @Nested class When_extracting_key {

            @Nested
            class And_unique_constraint_key_extraction_function_returns_null {
                @BeforeEach
                void setup() {
                    uniqueConstraint = new UniqueConstraint<>(TEST_NAME, ((String s) -> null));
                }

                @Test
                void It_should_throw_illegal_argument_exception() {
                    Assertions.assertThrows(IllegalArgumentException.class, () -> {
                        uniqueConstraint.extractKey("TEST");
                    });
                }
            }

            @Nested class And_item_is_null {
                @BeforeEach
                void setup() {
                    uniqueConstraint = new UniqueConstraint<>(TEST_NAME, ((String s) -> null));
                }

                @Test
                void It_should_throw_null_pointer_exception() {
                    Assertions.assertThrows(NullPointerException.class, () -> {
                        uniqueConstraint.extractKey(null);
                    });
                }
            }

            @Nested class And_key_is_blank_string {
                @BeforeEach
                void setup() {
                    uniqueConstraint = new UniqueConstraint<>(TEST_NAME, ((String s) -> s));
                }

                @Test
                void It_should_throw_illegal_argument_exception() {
                    Assertions.assertThrows(IllegalArgumentException.class, () -> {
                        uniqueConstraint.extractKey("");
                    });
                }
            }

            @Nested class And_key_is_valid {
                @BeforeEach
                void setup() {
                    uniqueConstraint = new UniqueConstraint<>(TEST_NAME, ((String s) -> s));
                }

                @Test
                void It_should_return_key() {
                    Assertions.assertEquals(TEST_NAME, uniqueConstraint.extractKey(TEST_NAME));
                }
            }
        }
    }

    @Nested class When_constructing_unique_constraint {
        UniqueConstraint<String> uniqueConstraint;

        @Test
        void It_should_require_name_not_be_null() {
            Assertions.assertThrows(NullPointerException.class, () -> {
                uniqueConstraint = new UniqueConstraint<>(null, ((String s) -> null));
            });
        }

        @Test
        void It_should_require_name_not_be_blank() {
            Assertions.assertThrows(IllegalArgumentException.class, () -> {
                uniqueConstraint = new UniqueConstraint<>("", ((String s) -> s));
            });
        }

        @Test
        void It_should_require_key_extractor_not_be_null() {
            Assertions.assertThrows(NullPointerException.class, () -> {
                uniqueConstraint = new UniqueConstraint<>(TEST_NAME, null);
            });
        }
    }
}
