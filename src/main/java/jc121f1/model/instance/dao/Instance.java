package jc121f1.model.instance.dao;

import jc121f1.model.instance.InstanceState;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Builder
public class Instance {
    private String name;

    private int cpu;

    private int memory;

    private String id;

    @Setter
    private InstanceState state;

    private Instant createdAt;
}
