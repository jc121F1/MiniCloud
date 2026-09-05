package jc121f1.model.instance.api.request;

import lombok.Builder;

import javax.annotation.Nullable;

@Builder
public record StopInstanceRequest(@Nullable String instanceId, @Nullable String name) {
}
