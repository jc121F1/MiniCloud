package jc121f1.wbs.exceptions.customererrors;

public abstract class CustomerFacingError {

    private final String message;

    public CustomerFacingError(String message) {
        this.message = message;
    }

    String getMessage() {
        return message;
    }

    public abstract int getStatusCode();
}
