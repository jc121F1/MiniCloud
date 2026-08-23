package jc121f1.model.instance.api.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.annotation.Nullable;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class GetInstanceRequest {
    @Nullable
    private String instanceId;
    @Nullable
    private String name;

    public boolean hasName() {
        return name != null;
    }

    public boolean hasInstanceId() {
        return instanceId != null;
    }

    public boolean hasIdentifier() {
        return this.hasName() || this.hasInstanceId();
    }
}
