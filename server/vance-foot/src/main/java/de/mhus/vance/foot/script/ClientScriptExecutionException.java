package de.mhus.vance.foot.script;

public class ClientScriptExecutionException extends RuntimeException {

    public enum ErrorClass {
        GUEST_EXCEPTION,
        HOST_EXCEPTION,
        RESOURCE_EXHAUSTED,
        TIMEOUT,
        CANCELLED,
    }

    private final ErrorClass errorClass;

    public ClientScriptExecutionException(ErrorClass errorClass, String message, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }
}
