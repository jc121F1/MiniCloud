package jc121f1.wbs.exceptions.customererrors;

public class ResourceNotFoundError extends CustomerFacingError {
    public ResourceNotFoundError(String message) {
        super(message);
    }
    @Override
    public int getStatusCode() {
        return 404;
    }
}
