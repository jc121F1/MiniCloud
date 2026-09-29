package jc121f1.common.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Bean Validation for simple constraints plus typed checks for request relationships. */
public final class RequestValidator {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private RequestValidator() { }

    public static <T> T validate(T request) {
        if (request == null) {
            throw new RequestValidationException(List.of(new FieldViolation("request", "required")));
        }
        List<FieldViolation> violations = VALIDATOR.validate(request).stream()
                .map(item -> new FieldViolation(item.getPropertyPath().toString(), item.getMessage()))
                .distinct()
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::code))
                .toList();
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
        return request;
    }

    /** Runs request-specific cross-field checks after its Jakarta field constraints. */
    public static <T> T validate(T request, Consumer<T> shapeValidator) {
        T validRequest = validate(request);
        shapeValidator.accept(validRequest);
        return validRequest;
    }

    /** Typed cross-field rules stay explicit at the owning service boundary. */
    public static void requireAtLeastOne(boolean firstPresent, String firstField,
                                         boolean secondPresent, String secondField) {
        if (!firstPresent && !secondPresent) {
            throw new RequestValidationException(List.of(
                    new FieldViolation(firstField, "one_required"),
                    new FieldViolation(secondField, "one_required")));
        }
    }
}
