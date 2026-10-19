package de.mhus.vance.shared.settings;

import de.mhus.vance.shared.cache.TtlTenantCache;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * A short-lived cache in front of the settings cascade, for the one shape of
 * lookup that happens on every write: "how long do we keep this?".
 *
 * <h2>Why this exists</h2>
 *
 * The activity feed and the usage ledger both compute an expiry per row, from
 * {@code SettingService.getStringValueCascade} — which has no cache and costs
 * up to three Mongo reads. The feed does it once per event; the ledger does it
 * twice per model-call <em>attempt</em>, retries included. A chat turn with one
 * retry therefore paid up to a dozen extra reads to look up two numbers that
 * change approximately never, and both subsystems arrived at that shape
 * independently — which is what makes it a convention worth fixing once rather
 * than a detail in one file.
 *
 * <h2>Why a TTL and not an eviction hook</h2>
 *
 * A retention change is not urgent: it decides when rows written from now on
 * expire, and being a minute late costs a minute of rows keeping the previous
 * horizon. An eviction path would have to reach every pod, which is a
 * cross-pod concern for a number nobody watches. {@link #invalidate()} exists
 * for tests and for an explicit admin action, not as the primary mechanism.
 *
 * <h2>What is deliberately not here</h2>
 *
 * Not a general settings cache. {@code SettingService} reads are correctness-
 * relevant in most places — a stale credential or a stale feature flag is a
 * bug, and a cache in front of all of them would need invalidation to be
 * exact. This one is scoped by construction: the caller supplies the key, the
 * value is an {@code int}, and the only readers are retention paths.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionSettingCache {

    /**
     * How long an entry is trusted. Long enough that a busy pod does one
     * lookup per key per minute instead of one per write, short enough that an
     * operator who changes a retention setting sees it take effect while still
     * looking at the screen.
     */
    static final Duration TTL = Duration.ofMinutes(1);

    private final SettingService settingService;
    private final TtlTenantCache<String, Integer> cache = new TtlTenantCache<>(TTL);

    /**
     * The effective retention in days for {@code (tenant, project)}, or
     * {@code fallback} when the setting is unset or unreadable.
     *
     * <p>The tri-state of the retention convention is preserved untouched:
     * {@code > 0} days, {@code 0} infinite, {@code < 0} disabled. Clamping and
     * interpretation stay with the caller — this only removes the round-trip.
     */
    public int days(String tenantId, @Nullable String projectId, String key, int fallback) {
        String cacheKey = tenantId + '\0' + (projectId == null ? "" : projectId) + '\0' + key;
        Integer cached = cache.get(cacheKey, () -> read(tenantId, projectId, key, fallback));
        return cached == null ? fallback : cached;
    }

    /** Drops every entry — for tests and for an explicit reload action. */
    public void invalidate() {
        cache.invalidateAll();
    }

    private int read(String tenantId, @Nullable String projectId, String key, int fallback) {
        String raw = settingService.getStringValueCascade(tenantId, projectId, /*thinkProcessId*/ null, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            log.warn("Retention setting '{}' is not an integer ('{}'), falling back to {}d", key, raw, fallback);
            return fallback;
        }
    }
}
