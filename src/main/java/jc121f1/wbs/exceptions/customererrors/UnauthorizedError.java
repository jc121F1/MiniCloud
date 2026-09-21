package jc121f1.wbs.exceptions.customererrors;

public class UnauthorizedError extends CustomerFacingError {
    public UnauthorizedError(String message) {
        super(message);
    }

    @Override
    public int getStatusCode() {
        return 401;
    }
}
