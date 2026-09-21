package jc121f1.services.authz.exceptions;

/** Storage failure, distinct from a permission denial; maps to a generic HTTP 500. */
public class AuthorizationStoreException extends RuntimeException {
    public AuthorizationStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
