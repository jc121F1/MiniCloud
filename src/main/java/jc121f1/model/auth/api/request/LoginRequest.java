package jc121f1.model.auth.api.request;

import io.javalin.openapi.OpenApiRequired;
import lombok.Builder;

@Builder
public record LoginRequest(@OpenApiRequired String email, @OpenApiRequired String password) {
}
