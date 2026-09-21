package jc121f1.services.authz.exceptions;

/** Invalid policy content or management input; maps to HTTP 400. */
public class PolicyValidationException extends RuntimeException {
    public PolicyValidationException(String message) {
        super(message);
    }
}
