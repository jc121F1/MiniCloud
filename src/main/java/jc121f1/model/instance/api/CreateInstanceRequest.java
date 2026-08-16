package jc121f1.model.instance.api;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CreateInstanceRequest {
    private String name;

    private int cpu;

    private int memory;
}
