package de.mhus.vance.brain.discovery;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * In-memory cache layer over {@link SourceCatalogBuilder}. First call
 * per tenant/project scope builds the snapshot; subsequent calls hit
 * the cache until {@link #invalidate} or {@link #invalidateAll} is
 * invoked.
 *
 * <p>v1 deliberately keeps the cache process-local. The Mongo-persisted
 * {@code DiscoveryCatalogDocument} promised in the spec lives in v2 —
 * needed only once we run multi-pod and want a single source of
 * truth across instances.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourceCatalogService {

    private final SourceCatalogBuilder builder;

    private final Map<String, CatalogSnapshot> cache = new ConcurrentHashMap<>();

    /**
     * Returns the rendered catalog Markdown for the given scope. The
     * snapshot is built once per cache key (tenant + project) and
     * reused until invalidated.
     */
    public String renderForTenant(String tenantId, @Nullable String projectId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String key = cacheKey(tenantId, projectId);
        CatalogSnapshot snap = cache.computeIfAbsent(key, k -> {
            log.debug("SourceCatalogService: building catalog for {}", k);
            return builder.build(tenantId, projectId);
        });
        return snap.markdown();
    }

    /** Returns the cached snapshot, building it if absent. */
    public CatalogSnapshot snapshotFor(String tenantId, @Nullable String projectId) {
        renderForTenant(tenantId, projectId);
        return cache.get(cacheKey(tenantId, projectId));
    }

    /**
     * Drop cached snapshots for a tenant. Both the tenant-only scope
     * and every project entry under that tenant are removed — a manual
     * edit anywhere in the cascade should be reflected on the next
     * call. Document-save hooks can wire this in later; not needed in
     * v1 because the cache is process-local and short-lived.
     */
    public void invalidate(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        String prefix = tenantId + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Drop everything. Mostly useful in tests. */
    public void invalidateAll() {
        cache.clear();
    }

    private static String cacheKey(String tenantId, @Nullable String projectId) {
        return tenantId + ":" + (projectId == null || projectId.isBlank() ? "" : projectId);
    }
}
