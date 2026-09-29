package jc121f1.model.instance.api.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Builder
@JsonIgnoreProperties("accountId")
public record CreateInstanceRequest(@NotBlank(message = "required") String name,
                                    @Positive(message = "must_be_positive") int cpu,
                                    @Positive(message = "must_be_positive") int memory) {

}
