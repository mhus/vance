package de.mhus.vance.brain.ai.audio;

/**
 * Thrown by audio providers and the dispatch layer when an audio call
 * fails at the wire (provider error, timeout, decoding failure) or
 * when the selected adapter cannot perform the requested operation.
 * The Hotblack service maps this into the public JSON error shape
 * ({@code {"error": "...", "message": "...", "retryable": ...}}).
 */
public class AiAudioException extends RuntimeException {

    /** True when the adapter was asked for an operation it does not
     *  implement (e.g. transcription on a TTS-only adapter). */
    private final boolean unsupportedOp;

    public AiAudioException(String message) {
        super(message);
        this.unsupportedOp = false;
    }

    public AiAudioException(String message, Throwable cause) {
        super(message, cause);
        this.unsupportedOp = false;
    }

    private AiAudioException(String message, boolean unsupportedOp) {
        super(message);
        this.unsupportedOp = unsupportedOp;
    }

    /** Factory for the default-fail of an unimplemented provider op. */
    public static AiAudioException unsupportedOperation(String providerName, String operation) {
        return new AiAudioException("The " + providerName + " adapter does not support " + operation, true);
    }

    /** {@code true} when this is an unimplemented-operation failure. */
    public boolean isUnsupportedOp() {
        return unsupportedOp;
    }
}
