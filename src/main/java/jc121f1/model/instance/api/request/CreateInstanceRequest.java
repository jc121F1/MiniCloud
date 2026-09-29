package jc121f1.model.instance.api.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

@Builder
@JsonIgnoreProperties("accountId")
public record CreateInstanceRequest(String name, int cpu, int memory) {

}
