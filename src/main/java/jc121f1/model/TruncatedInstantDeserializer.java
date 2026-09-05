package jc121f1.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import io.javalin.openapi.OpenApiIgnore;

import java.io.IOException;
import java.time.Instant;

public class TruncatedInstantDeserializer extends JsonDeserializer<Instant> {

    @Override
    @OpenApiIgnore
    public Instant deserialize(
            JsonParser parser,
            DeserializationContext context) throws IOException {

        return Instant.parse(parser.getText());
    }
}