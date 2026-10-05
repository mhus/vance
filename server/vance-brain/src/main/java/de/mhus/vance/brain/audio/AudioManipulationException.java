package de.mhus.vance.brain.audio;

/**
 * Thrown by {@link AudioManipulationService} when an audio-edit call
 * fails. The tool layer maps this into the public JSON error shape
 * ({@code {"error": "...", "message": "...", "retryable": ...}}).
 *
 * <p>The {@link Reason} vocabulary is fixed and mirrors
 * {@code ImageManipulationException} — see
 * {@code planning/audio-edit-tools.md} §4.
 */
public class AudioManipulationException extends RuntimeException {

    public enum Reason {
        SOURCE_NOT_FOUND(false),
        NOT_AUDIO(false),
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
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Reason reason;

    public AudioManipulationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AudioManipulationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
