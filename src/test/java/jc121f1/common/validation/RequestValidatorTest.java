package jc121f1.common.validation;

import jakarta.validation.constraints.NotBlank;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class RequestValidatorTest {
    record Sample(@NotBlank(message = "required") String username,
                  @NotBlank(message = "required") String password) { }

    @Test
    void reports_sorted_field_codes_without_rejected_values() {
        RequestValidationException error = Assertions.catchThrowableOfType(
                () -> RequestValidator.validate(new Sample("", "secret-value")),
                RequestValidationException.class);

        Assertions.assertThat(error.violations())
                .containsExactly(new FieldViolation("username", "required"));
        Assertions.assertThat(error.getMessage()).doesNotContain("secret-value");
    }

    @Test
    void typed_cross_field_rule_returns_only_stable_field_details() {
        RequestValidationException error = Assertions.catchThrowableOfType(
                () -> RequestValidator.requireAtLeastOne(false, "name", false, "instanceId"),
                RequestValidationException.class);

        Assertions.assertThat(error.violations()).containsExactly(
                new FieldViolation("name", "one_required"),
                new FieldViolation("instanceId", "one_required"));
        Assertions.assertThat(error.violations()).isEqualTo(List.copyOf(error.violations()));
    }
}
