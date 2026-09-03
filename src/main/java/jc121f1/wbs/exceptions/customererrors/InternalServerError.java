package jc121f1.wbs.exceptions.customererrors;

public class InternalServerError extends CustomerFacingError {
    public InternalServerError(String message) {
        super(message);
    }
    @Override
    public int getStatusCode() {
        return 500;
    }
}
