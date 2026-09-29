package jc121f1.wbs.exceptions.customererrors;

public class ForbiddenError extends CustomerFacingError {
    public ForbiddenError(String message) {
        super(message);
    }

    @Override
    public int getStatusCode() {
        return 403;
    }
}
