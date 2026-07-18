package de.mhus.vance.brain.damogran;

/**
 * Raised when a Damogran compose manifest is malformed or a compose run fails
 * in a way the caller should surface directly.
 *
 * <p>Kept independent of the tool layer's {@code ToolException} so the parser
 * and runner stay decoupled from any single invocation surface. Callers that
 * are LLM tools translate this into a {@code ToolException}; REST / Cortex
 * callers map it to an appropriate HTTP status.
 */
public class DamogranException extends RuntimeException {

    public DamogranException(String message) {
        super(message);
    }

    public DamogranException(String message, Throwable cause) {
        super(message, cause);
    }
}
