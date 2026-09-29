package jc121f1.model.auth.api.request;

import io.javalin.openapi.OpenApiRequired;
import lombok.Builder;
import jakarta.validation.constraints.NotBlank;

@Builder
public record LoginRequest(@OpenApiRequired @NotBlank(message = "required") String email,
                           @OpenApiRequired @NotBlank(message = "required") String password) {
}
