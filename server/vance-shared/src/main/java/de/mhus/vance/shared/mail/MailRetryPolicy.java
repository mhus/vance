package de.mhus.vance.shared.mail;

import java.time.Duration;
import java.time.Instant;

/**
 * Retry schedule for failed deliveries: after the n-th failed attempt the
 * entry is retried after {@code delays[n-1]}; once the delays are used up it
 * is given up. So {@code delays.length + 1} attempts happen in total.
 */
final class MailRetryPolicy {

    private final long[] delaysSeconds;

    MailRetryPolicy(int[] delaysSeconds) {
        this.delaysSeconds = new long[delaysSeconds.length];
        for (int i = 0; i < delaysSeconds.length; i++) {
            this.delaysSeconds[i] = Math.max(0, delaysSeconds[i]);
        }
    }

    /** {@code attemptsFailed} failures have used up every retry of the schedule. */
    boolean exhausted(int attemptsFailed) {
        return attemptsFailed > delaysSeconds.length;
    }

    /** When to retry after {@code attemptsFailed} failed attempts (1-based). */
    Instant nextAttemptAt(int attemptsFailed, Instant now) {
        int index = Math.min(Math.max(attemptsFailed, 1), delaysSeconds.length) - 1;
        return now.plus(Duration.ofSeconds(delaysSeconds[index]));
    }
}
