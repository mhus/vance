package de.mhus.vance.brain.oauthserver;

/**
 * The {@code client_id} (or the document behind it) cannot be accepted. The
 * message is safe to show — it describes the client, never server internals.
 */
public class InvalidClientException extends RuntimeException {

    public InvalidClientException(String message) {
        super(message);
    }

    public InvalidClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
