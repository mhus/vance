package de.mhus.vance.brain.ai.decision;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Failure of a decision call at the wire or in the dispatch layer.
 * Carries a {@link Failure} class so the service can decide between
 * retry, back-off and a caller-facing error without parsing messages.
 */
public class AiDecisionException extends RuntimeException {

    /** What went wrong — drives retry and the public error code. */
    public enum Failure {
        /** 400/422 — the request itself is wrong; retrying cannot help. */
        INVALID_REQUEST(false),
        /** The model id is unknown to the provider (404, or a 400 that says so). */
        MODEL_NOT_FOUND(false),
        /** 401/403 or a missing key. */
        AUTH(false),
        /** 429 — back off ({@link #retryAfter()} when the provider sent one). */
        RATE_LIMITED(true),
        /** 5xx / 529 — the provider is overloaded or broken. */
        PROVIDER_UNAVAILABLE(true),
        /** HTTP timeout. */
        TIMEOUT(true),
        /** Connection-level I/O failure. */
        TRANSPORT(true),
        /** 200 with a body that does not match the contract. */
        PROTOCOL(false),
        /** No adapter is registered for the resolved instance/protocol. */
        NO_ADAPTER(false),
        /** The thread was interrupted while waiting. */
        INTERRUPTED(false);

        private final boolean retryable;

        Failure(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Failure failure;
    private final @Nullable Integer httpStatus;
    private final @Nullable Duration retryAfter;

    public AiDecisionException(Failure failure, String message) {
        this(failure, message, null, null, null);
    }

    public AiDecisionException(Failure failure, String message, @Nullable Throwable cause) {
        this(failure, message, null, null, cause);
    }

    public AiDecisionException(
            Failure failure,
            String message,
            @Nullable Integer httpStatus,
            @Nullable Duration retryAfter,
            @Nullable Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public Failure failure() {
        return failure;
    }

    public boolean retryable() {
        return failure.retryable();
    }

    public @Nullable Integer httpStatus() {
        return httpStatus;
    }

    /** Provider-requested wait before the next attempt, if any. */
    public @Nullable Duration retryAfter() {
        return retryAfter;
    }
}
