package de.mhus.vance.brain.fook.upstream;

/**
 * Thrown by {@link TicketProvider} implementations when an
 * upstream call cannot complete. Use {@link #isRetryable} to tell
 * the sender-tick whether the next tick should try again
 * (transient — network blip, rate-limit) or whether the ticket
 * should go to {@link de.mhus.vance.brain.fook.FookTicketService#STATUS_FAILED}
 * (permanent — bad credentials, repo not found, malformed payload).
 */
public class ProviderException extends RuntimeException {

    private final boolean retryable;

    public ProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ProviderException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
