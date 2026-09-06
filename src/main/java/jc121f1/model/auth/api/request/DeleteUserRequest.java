package jc121f1.model.auth.api.request;

import lombok.Builder;

import javax.annotation.Nullable;

@Builder
public record DeleteUserRequest(@Nullable String userId, @Nullable String email) {
}
