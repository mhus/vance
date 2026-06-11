package de.mhus.vance.brain.image;

/**
 * Thrown by {@link ImageManipulationService} when an image-manipulation
 * call fails. The tool layer maps this into the public JSON error shape
 * ({@code {"error": "...", "message": "...", "retryable": ...}}).
 *
 * <p>The {@link Reason} vocabulary is fixed — see
 * {@code specification/image-manipulation.md} §8.
 */
public class ImageManipulationException extends RuntimeException {

    public enum Reason {
        SOURCE_NOT_FOUND(false),
        NOT_AN_IMAGE(false),
        FORMAT_UNSUPPORTED(false),
        PARAMETER_INVALID(false),
        LIMIT_EXCEEDED(false),
        TARGET_BLOCKED(false),
        PROCESSING_ERROR(true),
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
            return name().toLowerCase();
        }
    }

    private final Reason reason;

    public ImageManipulationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ImageManipulationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
