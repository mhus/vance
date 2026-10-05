package de.mhus.vance.toolpack.research;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Thrown by a {@link SearchProviderInstance} when the provider reports an
 * <em>empty quota</em> — HTTP 402, "out of credits", a depleted per-key
 * budget. {@code Exa} answers 402 when the team's credits are gone;
 * {@code Firecrawl} refuses with a credit error once the plan is spent.
 *
 * <p>This is a <em>known, terminating state</em>, not a failure to analyse.
 * The dispatcher therefore does <b>not</b> hand it to {@code AgrajagChecker}
 * (which would classify the provider "technically broken" and cool it down
 * for a flat 24h). Instead it sets a quota cooldown that ends at
 * {@link #resetsAt()} — or after 24h when the provider did not say — and
 * continues the cascade to the next candidate. That is exactly the
 * "exhaustion fallback" behaviour: a spent source answers by stepping aside,
 * not by taking the search down with it.
 *
 * <p>Distinct from a 429 rate limit: that one is temporary pressure and a
 * hard failure ({@link SearchProviderHttpException}), cooled down via
 * {@code Retry-After}.
 */
public class SearchQuotaExceededException extends RuntimeException {

    private final @Nullable Instant resetsAt;

    public SearchQuotaExceededException(String message) {
        this(message, null);
    }

    /**
     * @param resetsAt when the provider said quota comes back (billing
     *                 period reset, per-key budget window) — {@code null}
     *                 when it did not say
     */
    public SearchQuotaExceededException(String message, @Nullable Instant resetsAt) {
        super(message);
        this.resetsAt = resetsAt;
    }

    public @Nullable Instant resetsAt() {
        return resetsAt;
    }
}
