package jc121f1.model.instance.dao;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jc121f1.model.instance.InstanceState;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = false)
@JsonDeserialize(builder = Instance.InstanceBuilder.class)
public class Instance {
    private String name;

    private int cpu;

    private int memory;

    private String id;

    @Setter
    @EqualsAndHashCode.Exclude
    private InstanceState state;

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @JsonSerialize(using = InstantSerializer.class)
    @JsonDeserialize(using = InstantDeserializer.class)
    private Instant createdAt;

    public long memoryInBytes() {
        return (long) memory * 1024 * 1024;
    }

    @com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder(withPrefix = "")
    public static class InstanceBuilder {
    }

    public class InstantSerializer extends JsonSerializer<Instant> {

        @Override
        public void serialize(
                Instant value,
                JsonGenerator generator,
                SerializerProvider serializers) throws IOException {

            generator.writeString(
                    value.truncatedTo(ChronoUnit.SECONDS).toString()
            );
        }
    }

    public class InstantDeserializer extends JsonDeserializer<Instant> {

        @Override
        public Instant deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {

            return Instant.parse(parser.getText());
        }
    }
}
