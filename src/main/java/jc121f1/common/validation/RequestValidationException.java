package jc121f1.common.validation;

import java.util.List;
import java.util.stream.Collectors;

/** Service-neutral request-shape failure suitable for a customer-facing 400 response. */
public class RequestValidationException extends RuntimeException {
    private final List<FieldViolation> violations;

    public RequestValidationException(List<FieldViolation> violations) {
        super("Request validation failed: " + violations.stream()
                .map(FieldViolation::field).distinct().collect(Collectors.joining(", ")));
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
