package jc121f1.model.auth.api.request;

import jakarta.validation.constraints.NotBlank;

public record ExchangeServiceCredentialRequest(@NotBlank(message = "required") String credentialId,
                                               @NotBlank(message = "required") String secret) {
}
