package jc121f1.model.auth.api.request;

import lombok.Builder;

import javax.annotation.Nullable;

@Builder
public record CreateUserRequest(
        String userEmail,
        String password,
        @Nullable String accountId
) {
}
