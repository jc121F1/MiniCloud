package jc121f1.model.auth.api.request;

import lombok.Builder;
import jakarta.validation.constraints.NotBlank;

import javax.annotation.Nullable;

@Builder
public record CreateUserRequest(
        @NotBlank(message = "required") String userEmail,
        @NotBlank(message = "required") String password,
        @Nullable String accountId
) {
}
