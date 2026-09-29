package jc121f1.wbs.exceptions.customererrors;

import com.fasterxml.jackson.annotation.JsonProperty;
import jc121f1.common.validation.FieldViolation;

import java.util.List;

public class ValidationError extends CustomerFacingError {
    private final List<FieldViolation> fields;

    public ValidationError(String message) {
        super(message);
        this.fields = List.of();
    }

    public ValidationError(String message, List<FieldViolation> fields) {
        super(message);
        this.fields = List.copyOf(fields);
    }

    @JsonProperty("fields")
    public List<FieldViolation> getFields() {
        return fields;
    }

    @Override
    public int getStatusCode() {
        return 400;
    }
}
