package jc121f1.common.store.nosql;

import java.util.Objects;
import java.util.function.Function;

public record UniqueConstraint<T>(
        String name,
        Function<T, String> keyExtractor
) {

    public UniqueConstraint {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(keyExtractor, "keyExtractor must not be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "name must not be blank"
            );
        }
    }

    public String extractKey(T item) {
        Objects.requireNonNull(item, "item must not be null");

        String key = keyExtractor.apply(item);

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "Unique key for constraint '%s' must not be null or blank"
                            .formatted(name)
            );
        }

        return key;
    }
}