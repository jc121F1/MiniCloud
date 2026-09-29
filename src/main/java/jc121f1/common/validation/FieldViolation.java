package jc121f1.common.validation;

/** A safe, stable description of a request field failure. Never contains the rejected value. */
public record FieldViolation(String field, String code) {
}
