package de.mhus.vance.foot.auth;

/** Raised when a {@code .vance} config/credential file cannot be read or written. */
public class AccessStoreException extends RuntimeException {

    public AccessStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccessStoreException(String message) {
        super(message);
    }
}
