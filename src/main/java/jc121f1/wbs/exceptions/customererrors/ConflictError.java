package jc121f1.wbs.exceptions.customererrors;

public class ConflictError extends CustomerFacingError {
    public ConflictError(String message) {
        super(message);
    }
    @Override
    public int getStatusCode() {
        return 409;
    }
}
