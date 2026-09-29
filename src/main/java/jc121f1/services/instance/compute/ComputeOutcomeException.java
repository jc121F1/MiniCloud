package jc121f1.services.instance.compute;

import jc121f1.model.instance.ComputeStatus;

/** A failed lifecycle command whose actual Docker state was observed or could not be read. */
public class ComputeOutcomeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ComputeStatus observedStatus;

    public ComputeOutcomeException(String message, ComputeStatus observedStatus, Throwable cause) {
        super(message, cause);
        this.observedStatus = observedStatus;
    }

    /** Null means the backend could not determine the command's outcome. */
    public ComputeStatus observedStatus() {
        return observedStatus;
    }
}
