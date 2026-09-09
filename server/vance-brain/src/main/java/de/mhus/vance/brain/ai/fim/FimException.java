package de.mhus.vance.brain.ai.fim;

/**
 * Thrown when a Fill-In-the-Middle request cannot be served — the
 * FIM alias setting is unset at call time (callers should gate on
 * {@link FimCompletionService#isConfigured} first), the configured
 * model has no {@code fimTemplate} quirk (fail-closed against a
 * misrouted alias), or the provider call itself failed.
 */
public class FimException extends RuntimeException {

    public FimException(String message) {
        super(message);
    }

    public FimException(String message, Throwable cause) {
        super(message, cause);
    }
}
