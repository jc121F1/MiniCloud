package jc121f1.model.auth.api.request;

import jakarta.validation.constraints.NotBlank;

/** The authenticated account is the only account that can be transferred. */
public record TransferOwnershipRequest(@NotBlank(message = "required") String newOwnerUserId) {
}
