package de.mhus.vance.toolpack.research;

import de.mhus.vance.api.toolhealth.RetryAfterError;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A search provider's HTTP API answered non-2xx. Carries the facts a
 * caller needs beyond the status number itself:
 *
 * <ul>
 *   <li><b>{@code retryAfter}</b> — the parsed {@code Retry-After} hint,
 *       read by {@code AgrajagChecker} through {@link RetryAfterError} for
 *       {@code cooldown: header:retry-after} patterns. {@code null} when the
 *       response did not carry one.</li>
 *   <li><b>the message</b> — besides the status it should carry a short
 *       excerpt of the error body: Agrajag's {@code http-429-quota} pattern
 *       distinguishes "rate limit" from "out of credits" by reading the
 *       message text ({@code bodyContains}).</li>
 * </ul>
 *
 * <p>The message is built in the house shape {@code "… returned HTTP 429 …"}
 * so the status-extraction patterns in {@code AgrajagChecker} match even
 * when the throwable arrives wrapped in a cause chain.
 *
 * <p>Not for quota exhaustion: a 402/credit-error that <em>knows</em> the
 * quota is spent is {@link SearchQuotaExceededException} — a state, not a
 * failure. This type is for pressure and breakage (429, 5xx) where the
 * cascade falls through and the health stack may cool the instance down.
 */
public class SearchProviderHttpException extends RuntimeException implements RetryAfterError {

    private final int status;
    private final @Nullable Instant retryAfter;

    /**
     * @param message    full message including the {@code returned HTTP <status>}
     *                   shape and a body excerpt (see class doc)
     * @param status     the HTTP status code
     * @param retryAfter parsed {@code Retry-After}, or {@code null}
     */
    public SearchProviderHttpException(String message, int status, @Nullable Instant retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public int status() {
        return status;
    }

    @Override
    public @Nullable Instant retryAfter() {
        return retryAfter;
    }
}
