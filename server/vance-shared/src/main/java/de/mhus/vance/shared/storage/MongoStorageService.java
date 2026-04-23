package de.mhus.vance.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MongoDB-backed implementation of {@link StorageService}.
 *
 * <p>Blobs are split into fixed-size chunks (default 512 KB, configurable via
 * {@code vance.storage.chunk-size}). Every chunk is a separate document —
 * reads and writes stream through {@link ChunkedInputStream} /
 * {@link ChunkedOutputStream} so memory usage stays at {@code O(chunkSize)}.
 *
 * <p>{@link #delete} is soft: it writes a {@link StorageDelete} marker that
 * the {@link StorageCleanupScheduler} processes after a grace period.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MongoStorageService extends StorageService {

    private final StorageDataRepository storageDataRepository;
    private final StorageDeleteRepository storageDeleteRepository;

    @Value("${vance.storage.chunk-size:524288}")
    private int chunkSize;

    @Override
    public StorageInfo store(String tenantId, String path, InputStream stream) {
        return store(UUID.randomUUID().toString(), tenantId, path, stream);
    }

    @Transactional
    StorageInfo store(String storageId, String tenantId, String path, InputStream stream) {
        Date createdAt = new Date();
        try (ChunkedOutputStream out = new ChunkedOutputStream(
                storageDataRepository, storageId, tenantId, path, chunkSize, createdAt)) {
            stream.transferTo(out);
            long totalSize = out.getTotalBytesWritten();
            log.debug("Stored blob: storageId={} tenant={} path={} size={}",
                    storageId, tenantId, path, totalSize);
            return new StorageInfo(storageId, totalSize, createdAt, tenantId, path);
        } catch (IOException e) {
            log.error("Failed to store blob: path={}", path, e);
            throw new IllegalStateException("Failed to store blob", e);
        }
    }

    @Override
    public @Nullable InputStream load(String storageId) {
        if (storageId == null || storageId.isBlank()) {
            return null;
        }
        try {
            return new ChunkedInputStream(storageDataRepository, storageId);
        } catch (Exception e) {
            log.error("Failed to load blob: storageId={}", storageId, e);
            return null;
        }
    }

    @Override
    @Transactional
    public void delete(String storageId) {
        if (storageId == null || storageId.isBlank()) {
            return;
        }
        Date deletedAt = new Date(System.currentTimeMillis() + 5L * 60L * 1000L);
        storageDeleteRepository.save(StorageDelete.builder()
                .storageId(storageId)
                .deletedAt(deletedAt)
                .build());
        log.debug("Scheduled deletion: storageId={} at={}", storageId, deletedAt);
    }

    @Override
    @Transactional
    public StorageInfo update(String storageId, InputStream stream) {
        StorageData oldFinal = storageDataRepository.findByUuidAndIsFinalTrue(storageId);
        if (oldFinal == null) {
            throw new IllegalArgumentException("Storage id not found: " + storageId);
        }
        String path = oldFinal.getPath();
        String tenantId = oldFinal.getTenantId();

        StorageInfo newInfo = store(
                tenantId == null ? "" : tenantId,
                path == null ? "" : path,
                stream);
        delete(storageId);
        log.debug("Updated storage: oldId={} newId={}", storageId, newInfo.id());
        return newInfo;
    }

    @Override
    @Transactional
    public StorageInfo replace(String storageId, InputStream stream) {
        StorageData oldFinal = storageDataRepository.findByUuidAndIsFinalTrue(storageId);
        if (oldFinal == null) {
            throw new IllegalArgumentException("Storage id not found: " + storageId);
        }
        String path = oldFinal.getPath();
        String tenantId = oldFinal.getTenantId();
        String uuid = oldFinal.getUuid();

        List<StorageData> toDrop = storageDataRepository.findAllByUuid(uuid);
        toDrop.forEach(storageDataRepository::delete);

        return store(uuid,
                tenantId == null ? "" : tenantId,
                path == null ? "" : path,
                stream);
    }

    @Override
    public @Nullable StorageInfo info(String storageId) {
        if (storageId == null || storageId.isBlank()) {
            return null;
        }
        StorageData finalChunk;
        try {
            finalChunk = storageDataRepository.findByUuidAndIsFinalTrue(storageId);
        } catch (IncorrectResultSizeDataAccessException e) {
            log.error("Multiple final chunks found: storageId={}", storageId, e);
            List<StorageData> finals = storageDataRepository.findAllByUuidAndIsFinalTrue(storageId);
            finals.sort((a, b) -> {
                Date da = a.getCreatedAt();
                Date db = b.getCreatedAt();
                if (da == null && db == null) return 0;
                if (da == null) return 1;
                if (db == null) return -1;
                return db.compareTo(da);
            });
            finalChunk = finals.removeLast();
            for (StorageData dup : finals) {
                log.info("Deleting duplicate final chunk id={} createdAt={}",
                        dup.getId(), dup.getCreatedAt());
                storageDataRepository.delete(dup);
            }
        }
        if (finalChunk == null) {
            return null;
        }
        return new StorageInfo(
                storageId,
                finalChunk.getSize(),
                finalChunk.getCreatedAt() == null ? new Date(0) : finalChunk.getCreatedAt(),
                finalChunk.getTenantId(),
                finalChunk.getPath());
    }

    @Override
    @Transactional
    public @Nullable String duplicate(String sourceStorageId, String targetTenantId) {
        StorageInfo sourceInfo = info(sourceStorageId);
        if (sourceInfo == null) {
            log.error("Source blob not found: {}", sourceStorageId);
            return null;
        }
        InputStream sourceStream = load(sourceStorageId);
        if (sourceStream == null) {
            return null;
        }
        try {
            StorageInfo copy = store(
                    targetTenantId,
                    sourceInfo.path() == null ? "" : sourceInfo.path(),
                    sourceStream);
            log.debug("Duplicated storage: sourceId={} targetId={} targetTenant={}",
                    sourceStorageId, copy.id(), targetTenantId);
            return copy.id();
        } finally {
            try {
                sourceStream.close();
            } catch (IOException e) {
                log.warn("Error closing source stream", e);
            }
        }
    }
}
