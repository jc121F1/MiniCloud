package jc121f1.wbs.exceptions.customererrors;

import com.fasterxml.jackson.annotation.JsonProperty;

public abstract class CustomerFacingError {

    private final String message;

    public CustomerFacingError(String message) {
        this.message = message;
    }

    @JsonProperty("message")
    public String getMessage() {
        return message;
    }

    public abstract int getStatusCode();
}
