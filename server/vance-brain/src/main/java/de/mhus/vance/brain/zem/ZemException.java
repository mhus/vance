package de.mhus.vance.brain.zem;

import org.jspecify.annotations.Nullable;

/**
 * Caller-facing failure of a Zem decision. {@link Reason#wire()} is the
 * stable error code tools and scripts report; {@link #retryable()} says
 * whether trying again later can help (after the service's own retries
 * are spent).
 */
public class ZemException extends RuntimeException {

    /** Fixed vocabulary — also the {@code outcome} tag of the call metric. */
    public enum Reason {
        /** {@code ai.zem.enabled} is off in this scope. */
        DISABLED("disabled", false),
        /** The named profile does not exist, is not {@code engine: zem}, or is malformed. */
        INVALID_PROFILE("invalid_profile", false),
        /** A question violates the contract or the resolved model's limits. */
        INVALID_QUESTION("invalid_question", false),
        /** The state is missing or not a string/map/list. */
        INVALID_STATE("invalid_state", false),
        /** The model alias does not resolve to a usable decision model. */
        INVALID_CHOICE("invalid_choice", false),
        /** The provider rejected the request (a limit the catalog does not know). */
        INVALID_REQUEST("invalid_request", false),
        /** Missing or rejected credentials. */
        AUTH("auth", false),
        /** Still rate-limited after the retries. */
        RATE_LIMITED("rate_limited", true),
        TIMEOUT("timeout", true),
        /** Provider unavailable, transport failure or unparseable answer. */
        PROVIDER_ERROR("provider_error", true),
        /** The calling process was halted or the thread interrupted. */
        CANCELLED("cancelled", false);

        private final String wire;
        private final boolean retryable;

        Reason(String wire, boolean retryable) {
            this.wire = wire;
            this.retryable = retryable;
        }

        public String wire() {
            return wire;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Reason reason;

    public ZemException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ZemException(Reason reason, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public boolean retryable() {
        return reason.retryable();
    }
}
