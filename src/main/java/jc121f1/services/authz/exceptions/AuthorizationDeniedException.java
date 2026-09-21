package jc121f1.services.authz.exceptions;

/**
 * An authenticated principal lacks permission. Future HTTP integration should map
 * this to 403 (or a consistent resource-concealing 404), not authentication's 401.
 * Detailed decision reasons belong in internal audit records.
 */
public class AuthorizationDeniedException extends RuntimeException {
    public AuthorizationDeniedException() {
        super("Access denied");
    }
}
