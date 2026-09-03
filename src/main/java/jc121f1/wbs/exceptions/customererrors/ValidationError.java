package jc121f1.wbs.exceptions.customererrors;

public class ValidationError extends CustomerFacingError {
    public ValidationError(String message) {
        super(message);
    }

    @Override
    public int getStatusCode() {
        return 400;
    }
}
