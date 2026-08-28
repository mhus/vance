package de.mhus.vance.brain.fook.upstream;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@link TicketProvider} implementations when an
 * upstream call cannot complete. Use {@link #isRetryable} to tell
 * the sender-tick whether the next tick should try again
 * (transient — network blip, rate-limit) or whether the ticket
 * should go to {@link de.mhus.vance.brain.fook.FookTicketService#STATUS_FAILED}
 * (permanent — bad credentials, repo not found, malformed payload).
 *
 * <p>{@link #getRetryAfter} carries a rate-limit answer when the
 * provider gave one. It means more than "try later": the sender-tick
 * reads it as <b>stop this pass</b> and moves on to the next tick,
 * because a provider that just said "not before X" will refuse every
 * remaining ticket in the same loop. Without it, a provider capped at
 * one write per minute collects N−1 pointless refusals per pass.
 */
public class ProviderException extends RuntimeException {

    private final boolean retryable;
    private final @Nullable Duration retryAfter;

    public ProviderException(String message, boolean retryable) {
        this(message, null, retryable, null);
    }

    public ProviderException(String message, Throwable cause, boolean retryable) {
        this(message, cause, retryable, null);
    }

    /**
     * The rate-limit form. {@code retryAfter} implies retryable — a
     * limit that lifts at a stated time is by definition transient.
     */
    public static ProviderException rateLimited(String message, @Nullable Duration retryAfter) {
        return new ProviderException(message, null, true, retryAfter);
    }

    private ProviderException(
            String message,
            @Nullable Throwable cause,
            boolean retryable,
            @Nullable Duration retryAfter) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** How long the provider asked us to wait, when it said so. */
    public @Nullable Duration getRetryAfter() {
        return retryAfter;
    }
}
