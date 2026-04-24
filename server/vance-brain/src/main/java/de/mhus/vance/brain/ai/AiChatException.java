package de.mhus.vance.brain.ai;

/**
 * Thrown when an {@link AiChat} operation fails — provider error, quota,
 * timeout, interrupted streaming, decoding error, anything the caller cannot
 * recover from on their own.
 */
public class AiChatException extends RuntimeException {

    public AiChatException(String message) {
        super(message);
    }

    public AiChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
