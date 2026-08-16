package jc121f1.model.instance.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.annotation.Nullable;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class StopInstanceRequest {
    @Nullable
    private String instanceId;
    @Nullable
    private String name;
}
