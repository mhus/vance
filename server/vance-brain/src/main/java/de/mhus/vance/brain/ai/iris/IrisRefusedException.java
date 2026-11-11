package de.mhus.vance.brain.ai.iris;

import dev.langchain4j.exception.NonRetriableException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Iris refused a request for a reason that waiting a few seconds does not
 * change: the quota is empty, the five-hour window is used up, the model is not
 * part of the plan. It is a {@link NonRetriableException}, so
 * {@link de.mhus.vance.brain.ai.RetryPolicy} does not retry it and a fallback
 * chain moves on to the next entry at once (a BYOM model next to Iris, say).
 *
 * <p>The message is meant to be shown to the user as it is.
 */
public class IrisRefusedException extends NonRetriableException {

    private final String code;
    private final int status;
    private final @Nullable Duration retryAfter;

    public IrisRefusedException(String code, int status, String reason, @Nullable Duration retryAfter) {
        super(describe(code, reason, retryAfter));
        this.code = code;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    /** Iris' machine-readable error code, e.g. {@code quota_exhausted}. */
    public String code() {
        return code;
    }

    /** HTTP status Iris answered with. */
    public int status() {
        return status;
    }

    /** When the request may succeed again, if Iris said. */
    public @Nullable Duration retryAfter() {
        return retryAfter;
    }

    static String describe(String code, String reason, @Nullable Duration retryAfter) {
        StringBuilder sb = new StringBuilder("Iris refused the request (")
                .append(code)
                .append("): ")
                .append(reason);
        if (retryAfter != null && !retryAfter.isZero()) {
            sb.append("; it can be used again in ").append(humanize(retryAfter));
        }
        return sb.toString();
    }

    static String humanize(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        if (hours > 0) {
            return hours + " h " + minutes + " min";
        }
        if (minutes > 0) {
            return minutes + " min";
        }
        return Math.max(1, d.toSecondsPart()) + " s";
    }
}
