package de.mhus.vance.brain.ai;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Retry behaviour for a single chat-model entry. Covers transient
 * provider-side issues (rate limits, demand spikes, 5xx) — anything
 * else propagates immediately so genuine errors (bad-request, unknown
 * model, missing key) don't get sat on for minutes of pointless retry.
 *
 * <p>Pattern matching is plain substring against the exception's
 * {@link Throwable#getMessage() message} and full cause-chain. Default
 * patterns cover Gemini's common throttling phrases plus the standard
 * HTTP codes; tenants / recipes can supply their own list later
 * (Phase B).
 *
 * <p>Backoff is exponential, doubling each attempt, capped at
 * {@link #maxBackoff()}.
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        List<String> retryOnPatterns) {

    /**
     * Generous default — the call never blocks the caller longer than
     * the sum of backoffs and the underlying network timeouts. With
     * 5 attempts and 5/10/20/40/60s backoffs we ride out a typical
     * 30-90s demand spike without any human in the loop.
     */
    public static final RetryPolicy DEFAULT = new RetryPolicy(
            5,
            Duration.ofSeconds(5),
            Duration.ofSeconds(60),
            List.of(
                    "503", "429",
                    "high demand", "overloaded",
                    "RESOURCE_EXHAUSTED", "UNAVAILABLE",
                    "quota", "rate limit", "rate-limit",
                    // Gemini occasionally returns an empty response with
                    // neither text nor a tool-call — langchain4j surfaces
                    // it as this exact phrase. It's transient; retrying
                    // typically yields a real reply on the next attempt.
                    "neither with text nor with a function call"));

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be > 0");
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be >= initialBackoff");
        }
        if (retryOnPatterns == null) {
            retryOnPatterns = List.of();
        } else {
            retryOnPatterns = List.copyOf(retryOnPatterns);
        }
    }

    /**
     * Returns {@code true} if {@code error} matches any of the
     * {@link #retryOnPatterns}. Walks the cause-chain so wrapped
     * exceptions (langchain4j → its retry layer → HTTP client) are
     * still caught.
     */
    public boolean shouldRetry(Throwable error) {
        if (retryOnPatterns.isEmpty()) {
            return false;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) {
                continue;
            }
            String lower = msg.toLowerCase(Locale.ROOT);
            for (String pattern : retryOnPatterns) {
                if (lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Backoff for {@code attempt} (1-indexed). Exponential, capped at
     * {@link #maxBackoff()}.
     */
    public Duration backoffFor(int attempt) {
        if (attempt < 1) {
            return initialBackoff;
        }
        long millis = initialBackoff.toMillis();
        for (int i = 1; i < attempt; i++) {
            long next = millis * 2;
            if (next > maxBackoff.toMillis()) {
                return maxBackoff;
            }
            millis = next;
        }
        return Duration.ofMillis(millis);
    }
}
