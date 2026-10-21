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
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
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
 * and {@link #status} for the UI/status endpoint. A changed
 * {@code storage.quota.*} setting takes effect once the settings cache
 * expires ({@code vance.storage.quota.settings-ttl}).
 *
 * <p><b>Unknown sizes are capped, not waved through.</b> Most large
 * writes do not know their size up front (streaming gzip, a stream of
 * unknown length). For those {@link #check} answers with
 * {@link Check#remainingBytes()} — the room left under the limit — and
 * {@link MongoStorageService} aborts the write once the stream passes it.
 *
 * <p><b>Fail-open:</b> a settings read that returns no value, an
 * aggregation that throws, or a known {@code delta <= 0} path all return
 * {@link Check#allowed()} = {@code true} without a cap. The rationale is
 * documented in {@code planning/storage-quota.md} R1/R5 — never lock a
 * tenant out because of our infrastructure failing.
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
     * way to know the size in advance (streaming compression, a stream of
     * unknown length). The answer then carries the room left under the limit
     * as {@link Check#remainingBytes()}; the storage layer enforces it while
     * the stream drains. A tenant already at or over the limit is denied up
     * front.
     */
    public Check check(@Nullable String tenantId, long deltaBytes, long oldSizeBytes) {
        if (tenantId == null || tenantId.isBlank()) {
            return Check.allowed(0L, 0L, "no-tenant");
        }

        QuotaSettings settings = loadSettings(tenantId);
        if (settings.maxBytes() <= 0) {
            return Check.allowed(0L, 0L, "unlimited");
        }

        long oldBytes = oldSizeBytes > 0 ? oldSizeBytes : 0L;
        if (deltaBytes >= 0 && deltaBytes - oldBytes <= 0) {
            // Known size, not larger than the content it replaces: the write
            // cannot grow the footprint, so no aggregation needed.
            return Check.allowed(0L, settings.maxBytes(), "no-growth");
        }

        long currentUsage;
        try {
            currentUsage = precheckCache.get(tenantId, () -> aggregateUsage(tenantId));
        } catch (RuntimeException e) {
            log.warn("Storage quota aggregation failed for tenant={}, bypassing pre-check", tenantId, e);
            return Check.allowed(0L, settings.maxBytes(), "aggregation-failed-bypass");
        }

        // The replaced blob is still counted in storage_data until the cleanup
        // scheduler runs, so its bytes are room the new content may use.
        long remaining = settings.maxBytes() - currentUsage + oldBytes;
        if (deltaBytes < 0) {
            if (remaining <= 0) {
                return Check.denied(currentUsage, 0L, settings.maxBytes());
            }
            return Check.capped(currentUsage, settings.maxBytes(), remaining, "unknown-size-capped");
        }
        long effectiveDelta = deltaBytes - oldBytes;
        if (currentUsage + effectiveDelta > settings.maxBytes()) {
            return Check.denied(currentUsage, effectiveDelta, settings.maxBytes());
        }
        return Check.capped(currentUsage, settings.maxBytes(), remaining, "ok");
    }

    /** UI / status payload. Cached for {@code usageTtl}. */
    public Status status(String tenantId) {
        return usageCache.get(tenantId, () -> computeStatus(tenantId));
    }

    /**
     * Invalidate every cache layer for {@code tenantId} — for tests and an
     * explicit reload. The write path uses {@link #invalidateUsage}, which
     * leaves the settings cache alone.
     */
    public void invalidateAllCaches(@Nullable String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        settingsCache.invalidate(tenantId);
        invalidateUsage(tenantId);
    }

    /**
     * Drop the cached usage numbers for {@code tenantId}. Called by
     * {@link MongoStorageService} after a successful write so the next
     * pre-check on this pod sees the new size; other pods catch up after
     * {@code vance.storage.quota.precheck-ttl}.
     */
    public void invalidateUsage(@Nullable String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
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

    /**
     * Current usage of {@code tenantId} in bytes, regardless of whether a
     * limit is configured — {@link #status} skips the aggregation for an
     * unlimited tenant, an admin breakdown still wants the number. Shares
     * the pre-check cache. {@code -1} when the aggregation fails.
     */
    public long currentUsage(String tenantId) {
        try {
            return precheckCache.get(tenantId, () -> aggregateUsage(tenantId));
        } catch (RuntimeException e) {
            log.warn("Storage usage aggregation failed for tenant={}", tenantId, e);
            return -1L;
        }
    }

    /**
     * Bytes of the tenant's final chunks in {@code storage_data}, minus the
     * blobs whose deletion is already scheduled. {@link MongoStorageService#delete}
     * only writes a {@link StorageDelete} marker; the chunks stay until the
     * cleanup scheduler runs minutes later. Counting them would keep a
     * freshly emptied trash on the meter for that whole window.
     */
    long aggregateUsage(String tenantId) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("tenantId")
                        .is(tenantId)
                        .and("isFinal")
                        .is(true)),
                Aggregation.group().sum("size").as("total"));
        AggregationResults<SumResult> results = mongoTemplate.aggregate(agg, "storage_data", SumResult.class);
        SumResult r = results.getUniqueMappedResult();
        long total = r == null ? 0L : r.getTotal();
        return Math.max(0L, total - aggregatePendingDeletes(tenantId));
    }

    /**
     * Bytes of the tenant's blobs that carry a {@link StorageDelete} marker.
     * Starts from {@code storage_delete} — small, it only holds the markers
     * of the last scheduler interval — grouped by {@code storageId} so a blob
     * marked twice is subtracted once, then joins each blob's final chunk.
     */
    long aggregatePendingDeletes(String tenantId) {
        // Raw stages: Spring's field-reference check would reject
        // "finalChunk" after the $group, it only knows the exposed _id.
        org.bson.Document lookup = new org.bson.Document("from", "storage_data")
                .append("localField", "_id")
                .append("foreignField", "uuid")
                .append(
                        "pipeline",
                        java.util.List.of(
                                new org.bson.Document(
                                        "$match", new org.bson.Document("tenantId", tenantId).append("isFinal", true)),
                                new org.bson.Document("$project", new org.bson.Document("size", 1))))
                .append("as", "finalChunk");
        Aggregation agg = Aggregation.newAggregation(
                stage("$group", new org.bson.Document("_id", "$storageId")),
                stage("$lookup", lookup),
                stage("$unwind", "$finalChunk"),
                stage(
                        "$group",
                        new org.bson.Document("_id", null)
                                .append("total", new org.bson.Document("$sum", "$finalChunk.size"))));
        AggregationResults<SumResult> results = mongoTemplate.aggregate(agg, "storage_delete", SumResult.class);
        SumResult r = results.getUniqueMappedResult();
        return r == null ? 0L : r.getTotal();
    }

    private static AggregationOperation stage(String operator, Object body) {
        return context -> new org.bson.Document(operator, body);
    }

    private Status computeStatus(String tenantId) {
        QuotaSettings s = loadSettings(tenantId);
        if (s.maxBytes() <= 0) {
            // Nobody reads the usage of an unlimited tenant — skip the aggregation.
            return new Status(tenantId, -1L, 0L, -1, false, false);
        }
        long used;
        try {
            used = aggregateUsage(tenantId);
        } catch (RuntimeException e) {
            log.warn("Status aggregation failed for tenant={}", tenantId, e);
            used = -1L;
        }
        int percent = (int) Math.min(100L, Math.max(0L, used * 100L / s.maxBytes()));
        boolean exhausted = used >= s.maxBytes();
        boolean warning = s.warnPercent() > 0 && percent >= s.warnPercent();
        return new Status(tenantId, used, s.maxBytes(), percent, exhausted, warning);
    }

    // ──────────────────── records ────────────────────

    /**
     * One pre-check outcome. {@code remainingBytes} is the most the write may
     * store — {@link #UNCAPPED} when nothing limits it (unlimited tenant,
     * fail-open path, no-growth write).
     */
    public record Check(boolean allowed, long currentUsageBytes, long limitBytes, long remainingBytes, String reason) {

        /** {@link #remainingBytes()} when the write is not capped. */
        public static final long UNCAPPED = Long.MAX_VALUE;

        public static Check allowed(long used, long limit, String reason) {
            return new Check(true, used, limit, UNCAPPED, reason);
        }

        public static Check capped(long used, long limit, long remaining, String reason) {
            return new Check(true, used, limit, remaining, reason);
        }

        public static Check denied(long used, long delta, long limit) {
            return new Check(false, used, limit, 0L, "would-exceed:" + used + "+" + delta + ">" + limit);
        }
    }

    /** Settings read from the tenant's {@code _tenant} project. */
    public record QuotaSettings(long maxBytes, int warnPercent) {
        public static final QuotaSettings NONE = new QuotaSettings(0L, 0);
    }

    /**
     * UI / endpoint payload. {@code usedBytes == -1} means the usage was not
     * computed — the tenant is unlimited ({@code limitBytes == 0}) or the
     * aggregation failed.
     */
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
