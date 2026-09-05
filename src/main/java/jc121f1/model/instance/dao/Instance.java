package jc121f1.model.instance.dao;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.javalin.openapi.OpenApiIgnore;
import io.javalin.openapi.OpenApiRequired;
import jc121f1.model.instance.InstanceState;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Builder(toBuilder = true)
@JsonDeserialize(builder = Instance.InstanceBuilder.class)
@DynamoDbImmutable(builder = Instance.InstanceBuilder.class)
public record Instance(@OpenApiRequired String name, @OpenApiRequired int cpu, @OpenApiRequired int memory,
                       @OpenApiRequired String id, @EqualsAndHashCode.Exclude @OpenApiRequired InstanceState state,
                       @JsonIgnore @OpenApiRequired @EqualsAndHashCode.Exclude @JsonSerialize(using = InstantSerializer.class) @JsonDeserialize(using = InstantDeserializer.class) Instant createdAt) {
    @DynamoDbIgnore
    @OpenApiIgnore
    public long memoryInBytes() {
        return (long) memory * 1024 * 1024;
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class InstanceBuilder {

        @DynamoDbPartitionKey
        public InstanceBuilder id(String id) {
            this.id = id;
            return this;
        }

        @DynamoDbSecondaryPartitionKey(indexNames = "InstanceNameIndex")
        @OpenApiIgnore
        public InstanceBuilder name(String name) {
            this.name = name;
            return this;
        }
    }

    public static class InstantSerializer extends JsonSerializer<Instant> {

        @Override
        @OpenApiIgnore
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
        @OpenApiIgnore
        public Instant deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {

            return Instant.parse(parser.getText());
        }
    }

    @DynamoDbIgnore
    @OpenApiIgnore
    public Instance copyOf() {
        return this.toBuilder().build();
    }
}
