package jc121f1.model.instance.api.request;

import lombok.Builder;

@Builder
public record CreateInstanceRequest(String name, int cpu, int memory) {

}
