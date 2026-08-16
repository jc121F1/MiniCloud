package jc121f1.model.instance.dao;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jc121f1.model.instance.InstanceState;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@Builder
@EqualsAndHashCode(callSuper = false)
public class Instance {
    private String name;

    private int cpu;

    private int memory;

    private String id;

    @Setter
    private InstanceState state;

    @JsonIgnore
    private Instant createdAt;

    @JsonProperty("createdAt")
    public String createdAtAsString() {
        return createdAt.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
