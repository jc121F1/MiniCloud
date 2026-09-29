package jc121f1.model.auth.api.request;

import jakarta.validation.constraints.NotBlank;

public record InvalidateCredentialRequest(@NotBlank(message = "required") String credentialId) {
}
