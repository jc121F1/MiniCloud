package jc121f1.services.instance.exceptions;

public class ValidationException extends jc121f1.common.validation.RequestValidationException {
    private final String message;

    public ValidationException(String message) {
        this(message, java.util.List.of(new jc121f1.common.validation.FieldViolation("request", "invalid")));
    }

    public ValidationException(String message, java.util.List<jc121f1.common.validation.FieldViolation> violations) {
        super(violations);
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
