package de.mhus.vance.brain.ai;

import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Retry behaviour for a single chat-model entry. Covers transient
 * provider-side issues (rate limits, demand spikes, 5xx) — anything
 * else propagates immediately so genuine errors (bad-request, unknown
 * model, missing key) don't get sat on for minutes of pointless retry.
 *
 * <p>Classification is two-tier. langchain4j already sorts mapped HTTP
 * failures into {@link RetriableException} (5xx, 408, 429) and
 * {@link NonRetriableException} (4xx, auth, unknown model, DNS); that
 * typed verdict wins. Only errors carrying neither marker fall through
 * to substring matching against the exception's
 * {@link Throwable#getMessage() message} and full cause-chain. Default
 * patterns cover Gemini's common throttling phrases plus errors that
 * reach us outside langchain4j's mapper; tenants / recipes can supply
 * their own list later (Phase B).
 *
 * <p>Why the type check is not optional: langchain4j maps 502 to
 * {@code InternalServerException} whose message is the bare reason
 * phrase {@code "Bad Gateway"} — no status code anywhere in the text.
 * Substring matching alone therefore classified a gateway blip as a
 * genuine error, exhausted the one-entry chain and left the
 * think-process {@code BLOCKED} mid-turn.
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
                    // Reason phrases for 502/504. Spelled out rather than
                    // added as "502"/"504" because these patterns are
                    // plain substrings — a bare code would also match the
                    // digits inside an unrelated number in the message.
                    "bad gateway", "gateway timeout",
                    "high demand", "overloaded",
                    "RESOURCE_EXHAUSTED", "UNAVAILABLE",
                    "quota", "rate limit", "rate-limit",
                    // A request/read timeout is transient — the provider
                    // was slow or the connection stalled. Retry (ideally
                    // with a context-scaled timeout, see ModelInfo
                    // #scaledStreamTimeoutSeconds) rather than immediately
                    // exhausting the chain. Matches
                    // java.net.http.HttpTimeoutException ("request timed
                    // out") and langchain4j's TimeoutException.
                    "timed out", "timeout",
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
     * Returns {@code true} if the cause-chain carries langchain4j's
     * {@link RetriableException} marker, or — absent any typed verdict —
     * if a message in the chain matches one of the
     * {@link #retryOnPatterns}. Walking the chain is what catches
     * wrapped exceptions (our {@code AiChatException} → langchain4j →
     * HTTP client).
     */
    public boolean shouldRetry(Throwable error) {
        Boolean typed = typedVerdict(error);
        if (typed != null) {
            return typed;
        }
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
     * langchain4j's own classification of the failure, or {@code null}
     * when the chain carries no marker exception and the patterns get
     * to decide. A retriable marker anywhere in the chain wins over a
     * non-retriable one: providers wrap a transient transport failure
     * in a request-shaped exception often enough that the optimistic
     * reading is the useful one, and the attempt budget bounds the cost.
     */
    private static @Nullable Boolean typedVerdict(Throwable error) {
        boolean nonRetriable = false;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RetriableException) {
                return Boolean.TRUE;
            }
            if (t instanceof NonRetriableException) {
                nonRetriable = true;
            }
        }
        return nonRetriable ? Boolean.FALSE : null;
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
