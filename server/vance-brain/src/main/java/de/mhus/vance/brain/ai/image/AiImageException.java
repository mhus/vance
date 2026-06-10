package de.mhus.vance.brain.ai.image;

/**
 * Thrown when an image-generation operation fails — provider error,
 * quota exhausted, timeout, content-policy refusal, decoding error,
 * anything the caller cannot recover from on their own.
 */
public class AiImageException extends RuntimeException {

    public AiImageException(String message) {
        super(message);
    }

    public AiImageException(String message, Throwable cause) {
        super(message, cause);
    }
}
