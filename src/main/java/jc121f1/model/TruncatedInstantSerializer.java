package jc121f1.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.javalin.openapi.OpenApiIgnore;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class TruncatedInstantSerializer extends JsonSerializer<Instant> {

    private final ChronoUnit truncateTo;

    public TruncatedInstantSerializer() {
        this(ChronoUnit.MILLIS);
    }

    public TruncatedInstantSerializer(ChronoUnit truncateTo) {
        this.truncateTo = truncateTo;
    }

    @Override
    @OpenApiIgnore
    public void serialize(
            Instant value,
            JsonGenerator generator,
            SerializerProvider serializers) throws IOException {

        generator.writeString(value.truncatedTo(truncateTo).toString());
    }
}

