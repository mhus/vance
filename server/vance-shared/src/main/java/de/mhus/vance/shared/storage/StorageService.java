package de.mhus.vance.shared.storage;

import java.io.InputStream;
import java.util.Date;
import org.jspecify.annotations.Nullable;

/**
 * Blob storage abstraction — currently backed by MongoDB
 * ({@link MongoStorageService}). All blobs are tenant-scoped.
 */
public abstract class StorageService {

    /** Stores the stream and returns metadata including the new storage id. */
    public abstract StorageInfo store(String tenantId, String path, InputStream stream);

    /** Opens a streaming read over the blob addressed by {@code storageId}. */
    public abstract @Nullable InputStream load(String storageId);

    /**
     * Soft-deletes the blob — it becomes unreachable but stays on disk for a
     * short grace period so in-flight reads can finish (see
     * {@link StorageCleanupScheduler}).
     */
    public abstract void delete(String storageId);

    /**
     * Writes a new version of an existing blob under a fresh storage id;
     * the old version is soft-deleted. Callers reading the old id can finish
     * without interruption.
     */
    public abstract StorageInfo update(String storageId, InputStream stream);

    /**
     * Replaces the contents of an existing storage id in-place. Brief window
     * where reads may see inconsistent data — prefer {@link #update} unless
     * callers must keep the same id.
     */
    public abstract StorageInfo replace(String storageId, InputStream stream);

    public abstract @Nullable StorageInfo info(String storageId);

    /**
     * Copies the blob to {@code targetTenantId}. Returns the new storage id,
     * or {@code null} if the source could not be read.
     */
    public abstract @Nullable String duplicate(String sourceStorageId, String targetTenantId);

    public boolean exists(String storageId) {
        try {
            return info(storageId) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Metadata record returned from {@link #store}, {@link #info} and friends. */
    public record StorageInfo(
            String id,
            long size,
            Date createdAt,
            @Nullable String tenantId,
            @Nullable String path) {
    }
}
