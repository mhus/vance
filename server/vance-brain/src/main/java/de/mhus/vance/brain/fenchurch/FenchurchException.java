package de.mhus.vance.brain.fenchurch;

/**
 * Thrown by {@link FenchurchService} when an image-generation call
 * fails. The tool layer maps this into the public JSON error shape
 * ({@code {"error": "...", "message": "...", "retryable": ...}}).
 */
public class FenchurchException extends RuntimeException {

    /** Stable error tag — fixed vocabulary, matches the tool-result
     *  contract in {@code planning/fenchurch-service.md} §4.1. */
    public enum Reason {
        QUOTA_EXCEEDED(false),
        PROVIDER_ERROR(true),
        TIMEOUT(true),
        CONTENT_POLICY(false),
        CANCELLED(false),
        PROMPT_TOO_LONG(false),
        UNSUPPORTED_ASPECT_RATIO(false),
        DISABLED(false);

        private final boolean retryable;

        Reason(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }

        /** Lower-case wire form for the tool-result JSON. */
        public String wire() {
            return name().toLowerCase().replace('_', '_');
        }
    }

    private final Reason reason;

    public FenchurchException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FenchurchException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
