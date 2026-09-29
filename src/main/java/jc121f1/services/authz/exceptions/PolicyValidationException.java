package jc121f1.services.authz.exceptions;

import jc121f1.common.validation.FieldViolation;

import java.util.List;

/** Invalid policy content or management input; maps to HTTP 400. */
public class PolicyValidationException extends jc121f1.common.validation.RequestValidationException {
    private final String message;

    public PolicyValidationException(String message) {
        this(message, List.of(new FieldViolation("request", "invalid")));
    }

    public PolicyValidationException(String message, List<FieldViolation> violations) {
        super(violations);
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
