package jc121f1.model.auth.api.request;

/** The authenticated account is the only account that can be transferred. */
public record TransferOwnershipRequest(String newOwnerUserId) {
}
