package de.mhus.vance.brain.hotblack;

/**
 * Thrown by {@link HotblackService} when an audio call fails. The tool
 * layer maps this into the public JSON error shape
 * ({@code {"error": "...", "message": "...", "retryable": ...}}).
 */
public class HotblackException extends RuntimeException {

    /** Stable error tag — fixed vocabulary, matches the tool-result contract. */
    public enum Reason {
        QUOTA_EXCEEDED(false),
        PROVIDER_ERROR(true),
        TIMEOUT(true),
        CONTENT_POLICY(false),
        CANCELLED(false),
        TEXT_TOO_LONG(false),
        AUDIO_TOO_LONG(false),
        UNSUPPORTED_FORMAT(false),
        UNSUPPORTED_LANGUAGE(false),
        INVALID_CHOICE(false),
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
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Reason reason;

    public HotblackException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public HotblackException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
