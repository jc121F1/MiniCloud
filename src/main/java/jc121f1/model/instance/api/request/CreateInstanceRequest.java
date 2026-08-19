package jc121f1.model.instance.api.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateInstanceRequest {
    private String name;

    private int cpu;

    private int memory;
}
