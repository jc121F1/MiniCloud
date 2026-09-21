package jc121f1.services.authz.exceptions;

/** Missing policy or attachment target, after account authorization; maps to 404. */
public class PolicyNotFoundException extends RuntimeException {
    public PolicyNotFoundException(String message) {
        super(message);
    }
}
