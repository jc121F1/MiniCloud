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
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = false)
@JsonDeserialize(builder = Instance.InstanceBuilder.class)
@DynamoDbImmutable(builder = Instance.InstanceBuilder.class)
public class Instance {
    private final String name;

    private final int cpu;

    private final int memory;

    private final String id;

    @EqualsAndHashCode.Exclude
    private final InstanceState state;

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @JsonSerialize(using = InstantSerializer.class)
    @JsonDeserialize(using = InstantDeserializer.class)
    private final Instant createdAt;

    @DynamoDbIgnore
    public long memoryInBytes() {
        return (long) memory * 1024 * 1024;
    }

    @com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder(withPrefix = "")
    public static class InstanceBuilder {

        @DynamoDbPartitionKey
        public InstanceBuilder id(String id) {
            this.id = id;
            return this;
        }

        @DynamoDbSecondaryPartitionKey(indexNames = "InstanceNameIndex")
        public InstanceBuilder name(String name) {
            this.name = name;
            return this;
        }
    }

    public static class InstantSerializer extends JsonSerializer<Instant> {

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

    public static class InstantDeserializer extends JsonDeserializer<Instant> {

        @Override
        public Instant deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {

            return Instant.parse(parser.getText());
        }
    }

    @DynamoDbIgnore
    public Instance copyOf() {
        return this.toBuilder().build();
    }
}
