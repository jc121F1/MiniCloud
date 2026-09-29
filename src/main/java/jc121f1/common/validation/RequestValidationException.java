package jc121f1.common.validation;

import java.util.List;

/** Service-neutral request-shape failure suitable for a customer-facing 400 response. */
public class RequestValidationException extends RuntimeException {
    private final List<FieldViolation> violations;

    public RequestValidationException(List<FieldViolation> violations) {
        super("Request validation failed");
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
