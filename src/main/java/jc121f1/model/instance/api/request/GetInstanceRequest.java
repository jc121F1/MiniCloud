package jc121f1.model.instance.api.request;

import io.javalin.openapi.OpenApiIgnore;
import lombok.Builder;

import javax.annotation.Nullable;

@Builder
public record GetInstanceRequest(@Nullable String instanceId, @Nullable String name) {

    @OpenApiIgnore
    public boolean hasName() {
        return name != null;
    }
    @OpenApiIgnore
    public boolean hasInstanceId() {
        return instanceId != null;
    }
    @OpenApiIgnore
    public boolean hasIdentifier() {
        return this.hasName() || this.hasInstanceId();
    }
}
