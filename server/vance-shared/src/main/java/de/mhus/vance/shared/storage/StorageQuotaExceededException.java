package de.mhus.vance.shared.storage;

/**
 * Thrown when a storage write would push the tenant over its configured
 * {@code storage.quota.maxBytes}. Throws from the quota pre-check in
 * {@link MongoStorageService}; the caller is expected to surface it as a
 * 4xx to the user with a clear message about the limit and the setting to
 * raise.
 *
 * <p>The exception carries the live numbers so the message can name them
 * without a second read.
 *
 * @param tenant          tenant whose quota was violated
 * @param currentUsageBytes bytes already used at the moment of the check
 * @param deltaBytes      bytes the failing write would have added
 * @param limitBytes      configured limit in bytes (0 = unlimited, but
 *                          this exception is only thrown when {@code limitBytes > 0})
 */
public class StorageQuotaExceededException extends RuntimeException {

    private final String tenant;
    private final long currentUsageBytes;
    private final long deltaBytes;
    private final long limitBytes;

    public StorageQuotaExceededException(String tenant, long currentUsageBytes, long deltaBytes, long limitBytes) {
        super(formatMessage(tenant, currentUsageBytes, deltaBytes, limitBytes));
        this.tenant = tenant;
        this.currentUsageBytes = currentUsageBytes;
        this.deltaBytes = deltaBytes;
        this.limitBytes = limitBytes;
    }

    public String tenant() {
        return tenant;
    }

    public long currentUsageBytes() {
        return currentUsageBytes;
    }

    public long deltaBytes() {
        return deltaBytes;
    }

    public long limitBytes() {
        return limitBytes;
    }

    private static String formatMessage(String tenant, long used, long delta, long limit) {
        return "Tenant '" + tenant + "' storage quota exceeded: "
                + formatBytes(used) + " used + " + formatBytes(delta)
                + " write would exceed " + formatBytes(limit)
                + " limit. Free space or raise 'storage.quota.maxBytes'.";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }
}
