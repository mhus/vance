package de.mhus.vance.shared.lease;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Read view of one funnel counter, with its {@code _id}
 * ({@code <resource>|<level>|<scope>}) taken apart — the diagnostic
 * surfaces (Insights, admin shell) show the parts, never the raw key.
 *
 * @param key       full counter key
 * @param resource  resource key of the lease definition
 * @param level     {@code global}, {@code tenant}, {@code project} or {@code user}
 * @param scope     level scope: empty for global, the tenant id for tenant,
 *                  {@code <tenant>/<project>} or {@code <tenant>/<user>} below
 * @param count     slots currently booked on this level
 * @param updatedAt last write, {@code null} for a counter never written by a write op
 */
public record LeaseCounter(
        String key,
        String resource,
        String level,
        String scope,
        long count,
        @Nullable Instant updatedAt) {

    /**
     * Splits the counter key from the right: the level and the scope never
     * contain the separator, the resource key is free text and might.
     */
    public static LeaseCounter of(ResourceLeaseCounterDocument doc) {
        String key = doc.getId();
        int scopeSep = key.lastIndexOf('|');
        int levelSep = scopeSep <= 0 ? -1 : key.lastIndexOf('|', scopeSep - 1);
        if (levelSep < 0) {
            // Not written by this service — shown as-is rather than dropped.
            return new LeaseCounter(key, key, "", "", doc.getCount(), doc.getUpdatedAt());
        }
        return new LeaseCounter(
                key,
                key.substring(0, levelSep),
                key.substring(levelSep + 1, scopeSep),
                key.substring(scopeSep + 1),
                doc.getCount(),
                doc.getUpdatedAt());
    }

    /**
     * Whether this counter counts only the given tenant's holders. The
     * global level counts every tenant and belongs to none.
     */
    public boolean belongsToTenant(String tenantId) {
        return switch (level) {
            case "tenant" -> scope.equals(tenantId);
            case "project", "user" -> scope.startsWith(tenantId + "/");
            default -> false;
        };
    }

    /** The scope without the tenant prefix — project name or user login; the tenant id on the tenant level. */
    public String scopeWithinTenant() {
        int slash = scope.indexOf('/');
        return slash < 0 ? scope : scope.substring(slash + 1);
    }
}
