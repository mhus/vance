package de.mhus.vance.brain.ai.light;

/**
 * Thrown when a {@link LightLlmService} call cannot complete — recipe
 * not found / not marked internal, LLM provider exhausted, invalid
 * request, etc. See {@link SchemaValidationException} for the more
 * specific subclass that signals "LLM ran but couldn't satisfy the
 * caller's schema after maxAttempts retries".
 */
public class LightLlmException extends RuntimeException {

    public LightLlmException(String message) {
        super(message);
    }

    public LightLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
