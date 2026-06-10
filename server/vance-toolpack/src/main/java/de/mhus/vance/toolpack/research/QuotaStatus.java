package de.mhus.vance.toolpack.research;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot of an instance's remaining quota. Returned by
 * {@link SearchProviderInstance#currentQuota}; the dispatcher uses it
 * for the proactive zero-quota gate (instance with
 * {@code remaining == 0} gets a cooldown set without firing a request
 * that would just come back as 429).
 *
 * <p>Instances that don't expose a quota endpoint return
 * {@code Optional.empty()} from {@code currentQuota} — the dispatcher
 * skips the proactive check silently.
 */
public record QuotaStatus(
        long remaining,
        @Nullable Long limit,
        @Nullable Instant resetsAt,
        @Nullable Duration refreshInterval) { }
