package de.mhus.vance.shared.storage;

import de.mhus.vance.shared.cache.TtlTenantCache;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;

/**
 * Per-tenant storage quota. The limit lives as a setting
 * ({@code storage.quota.maxBytes}) on the tenant's {@code _tenant}
 * project, just like {@code ai.llm.budget.*} sits there for LLM cost
 * limits. The current usage is computed by aggregating
 * {@code storage_data} for the tenant, restricted to final chunks
 * (the only ones that carry a non-zero {@code size}).
 *
 * <p>The hook lives in {@link MongoStorageService}; this service is
 * the read-side. It exposes {@link #check} for the pre-check path
 * and {@link #status} for the UI/status endpoint, plus
 * {@link #invalidateAllCaches} for the cross-pod listener that fires
 * when the setting is written.
 *
 * <p><b>Fail-open:</b> a settings read that returns no value, an
 * aggregation that throws, or a {@code delta <= 0} path all return
 * {@link Check#allowed()} = {@code true}. The rationale is documented
 * in {@code planning/storage-quota.md} R1/R4/R5 — "im Zweifel für den
 * User", never lock a tenant out because of our infrastructure failing.
 */
@Service
@Slf4j
public class StorageQuotaService {

    static final String PREFIX = "storage.quota.";
    static final String KEY_MAX_BYTES = PREFIX + "maxBytes";
    static final String KEY_WARN_PERCENT = PREFIX + "warnPercent";

    private final SettingService settingService;
    private final MongoTemplate mongoTemplate;
    private final StorageQuotaProperties properties;

    /** Settings cache (maxBytes, warnPercent) — keyed by tenant. */
    private final TtlTenantCache<String, QuotaSettings> settingsCache;

    /** Pre-check aggregation cache — keyed by tenant. */
    private final TtlTenantCache<String, Long> precheckCache;

    /** UI status cache — keyed by tenant. */
    private final TtlTenantCache<String, Status> usageCache;

    public StorageQuotaService(
            SettingService settingService, MongoTemplate mongoTemplate, StorageQuotaProperties properties) {
        this.settingService = settingService;
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
        this.settingsCache = new TtlTenantCache<>(properties.getSettingsTtl());
        this.precheckCache = new TtlTenantCache<>(properties.getPrecheckTtl());
        this.usageCache = new TtlTenantCache<>(properties.getUsageTtl());
    }

    /**
     * Pre-check whether a write of {@code deltaBytes} for {@code tenant} is
     * allowed under the current quota. {@code oldSizeBytes} accounts for a
     * blob the write is replacing (its bytes are still counted in
     * {@code storage_data} until the cleanup scheduler runs); pass {@code -1}
     * for a new write that does not replace anything.
     *
     * <p>{@code deltaBytes == -1} signals "size unknown" — the caller has no
     * way to know the size in advance (e.g. multipart upload). We bypass the
     * pre-check and let the write through; the next periodic aggregation
     * will catch any resulting overrun (multi-pod race + R4).
     */
    public Check check(@Nullable String tenantId, long deltaBytes, long oldSizeBytes) {
        if (tenantId == null || tenantId.isBlank()) {
            return Check.allowed(0L, 0L, "no-tenant");
        }

        QuotaSettings settings = loadSettings(tenantId);
        if (settings.maxBytes() <= 0) {
            return Check.allowed(0L, 0L, "unlimited");
        }

        if (deltaBytes < 0) {
            // Caller does not know the size — R4: bypass. Must be checked
            // before the effectiveDelta computation, otherwise (delta=-1,
            // oldSize=-1) would collapse to no-growth and the unknown-size
            // signal would be lost.
            return Check.allowed(0L, settings.maxBytes(), "unknown-size-bypass");
        }

        long effectiveDelta = (deltaBytes > 0 ? deltaBytes : 0L) - (oldSizeBytes > 0 ? oldSizeBytes : 0L);
        if (effectiveDelta <= 0) {
            // Replace/update smaller than the previous content: the write
            // cannot grow the footprint, so no pre-check needed.
            return Check.allowed(0L, settings.maxBytes(), "no-growth");
        }

        long currentUsage;
        try {
            currentUsage = aggregateUsage(tenantId);
        } catch (RuntimeException e) {
            log.warn("Storage quota aggregation failed for tenant={}, bypassing pre-check", tenantId, e);
            return Check.allowed(0L, settings.maxBytes(), "aggregation-failed-bypass");
        }

        if (currentUsage + effectiveDelta > settings.maxBytes()) {
            return Check.denied(currentUsage, effectiveDelta, settings.maxBytes());
        }
        return Check.allowed(currentUsage, settings.maxBytes(), "ok");
    }

    /** UI / status payload. Cached for {@code usageTtl}. */
    public Status status(String tenantId) {
        return usageCache.get(tenantId, () -> computeStatus(tenantId));
    }

    /**
     * Invalidate every cache layer for {@code tenantId}. Called by the
     * {@code RoutedDocumentChangedEvent} listener in {@code vance-brain}
     * when {@code storage.quota.*} settings are written, and by
     * {@link MongoStorageService} after a successful write (so the next
     * pre-check on the same pod sees the new size).
     */
    public void invalidateAllCaches(@Nullable String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        settingsCache.invalidate(tenantId);
        precheckCache.invalidate(tenantId);
        usageCache.invalidate(tenantId);
    }

    // ──────────────────── internals ────────────────────

    private QuotaSettings loadSettings(String tenantId) {
        return settingsCache.get(tenantId, () -> readSettings(tenantId));
    }

    private QuotaSettings readSettings(String tenantId) {
        Map<String, String> raw = new HashMap<>();
        for (SettingDocument doc :
                settingService.findAll(tenantId, SettingService.SCOPE_PROJECT, ProjectService.TENANT_PROJECT_NAME)) {
            if (doc.getKey() != null
                    && doc.getKey().startsWith(PREFIX)
                    && !doc.getType().encrypted()
                    && doc.getValue() != null) {
                raw.put(doc.getKey(), doc.getValue());
            }
        }
        if (raw.isEmpty()) {
            return QuotaSettings.NONE;
        }
        long maxBytes = parseLong(raw.get(KEY_MAX_BYTES), 0L);
        int warnPercent = (int) parseLong(raw.get(KEY_WARN_PERCENT), 80L);
        // Clamp warnPercent to the spec'd 0..99 range; out-of-range is "off".
        if (warnPercent < 0 || warnPercent > 99) {
            warnPercent = 0;
        }
        if (maxBytes < 0) {
            maxBytes = 0L;
        }
        return new QuotaSettings(maxBytes, warnPercent);
    }

    private static long parseLong(@Nullable String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse long value: '{}'", raw, e);
            return defaultValue;
        }
    }

    /** Aggregates {@code storage_data} for the tenant's final chunks. */
    long aggregateUsage(String tenantId) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("tenantId")
                        .is(tenantId)
                        .and("isFinal")
                        .is(true)),
                Aggregation.group().sum("size").as("total"));
        AggregationResults<SumResult> results = mongoTemplate.aggregate(agg, "storage_data", SumResult.class);
        SumResult r = results.getUniqueMappedResult();
        return r == null ? 0L : r.getTotal();
    }

    private Status computeStatus(String tenantId) {
        QuotaSettings s = loadSettings(tenantId);
        long used;
        try {
            used = aggregateUsage(tenantId);
        } catch (RuntimeException e) {
            log.warn("Status aggregation failed for tenant={}", tenantId, e);
            used = -1L;
        }
        boolean unlimited = s.maxBytes() <= 0;
        int percent = unlimited ? -1 : (int) Math.min(100L, Math.max(0L, used * 100L / s.maxBytes()));
        boolean exhausted = !unlimited && used >= s.maxBytes();
        boolean warning = !unlimited && s.warnPercent() > 0 && percent >= s.warnPercent();
        return new Status(tenantId, used, s.maxBytes(), percent, exhausted, warning);
    }

    // ──────────────────── records ────────────────────

    /** One pre-check outcome. */
    public record Check(boolean allowed, long currentUsageBytes, long limitBytes, String reason) {
        public static Check allowed(long used, long limit, String reason) {
            return new Check(true, used, limit, reason);
        }

        public static Check denied(long used, long delta, long limit) {
            return new Check(false, used, limit, "would-exceed:" + used + "+" + delta + ">" + limit);
        }
    }

    /** Settings read from the tenant's {@code _tenant} project. */
    public record QuotaSettings(long maxBytes, int warnPercent) {
        public static final QuotaSettings NONE = new QuotaSettings(0L, 0);
    }

    /** UI / endpoint payload. {@code usedBytes == -1} signals aggregation failure. */
    public record Status(
            String tenant, long usedBytes, long limitBytes, int percentUsed, boolean exhausted, boolean warning) {}

    /** Aggregation result row. */
    public static class SumResult {
        private long total;

        public long getTotal() {
            return total;
        }

        public void setTotal(long total) {
            this.total = total;
        }
    }
}
