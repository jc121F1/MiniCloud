package jc121f1.services.authz.exceptions;

/** Stale revision or attempted deletion of an attached policy; maps to HTTP 409. */
public class PolicyConflictException extends RuntimeException {
    public PolicyConflictException(String message) {
        super(message);
    }
}
