package jc121f1.wbs.exceptions;

import jc121f1.wbs.exceptions.interfaces.CustomerFacingException;

public class ConflictException extends RuntimeException implements CustomerFacingException {
    public ConflictException(String message) {
        super(message);
        this.setStackTrace(new StackTraceElement[0]);
    }
    @Override
    public int getStatusCode() {
        return 409;
    }
}
