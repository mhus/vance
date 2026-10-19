package de.mhus.vance.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
    private final MongoTemplate mongoTemplate;
    private final StorageQuotaService storageQuotaService;

    @Value("${vance.storage.chunk-size:524288}")
    private int chunkSize;

    @Override
    public StorageInfo store(String tenantId, String path, InputStream stream) {
        return store(tenantId, path, stream, -1L);
    }

    @Override
    public StorageInfo store(String tenantId, String path, InputStream stream, long expectedBytes) {
        return storeWithQuota(UUID.randomUUID().toString(), tenantId, path, stream, expectedBytes, -1L);
    }

    /**
     * Internal write path with quota pre-check. {@code oldSizeBytes == -1}
     * signals "no previous blob to account for" (a fresh write); the public
     * {@link #update} and {@link #replace} paths compute and pass the actual
     * previous size where it matters.
     */
    @Transactional
    StorageInfo storeWithQuota(
            String storageId, String tenantId, String path, InputStream stream, long expectedBytes, long oldSizeBytes) {
        // Pre-check; throws StorageQuotaExceededException when the write would
        // exceed the configured limit. fail-open paths (R1, R4, R5) return
        // allowed() — see StorageQuotaService for the rationale.
        StorageQuotaService.Check check = storageQuotaService.check(tenantId, expectedBytes, oldSizeBytes);
        if (!check.allowed()) {
            throw new StorageQuotaExceededException(
                    tenantId, check.currentUsageBytes(), expectedBytes > 0 ? expectedBytes : 0L, check.limitBytes());
        }

        Date createdAt = new Date();
        try (ChunkedOutputStream out =
                new ChunkedOutputStream(storageDataRepository, storageId, tenantId, path, chunkSize, createdAt)) {
            stream.transferTo(out);
            long totalSize = out.getTotalBytesWritten();
            log.debug("Stored blob: storageId={} tenant={} path={} size={}", storageId, tenantId, path, totalSize);
            // Invalidate the pre-check cache on this pod so the very next
            // pre-check sees the new size (multi-pod race tolerance).
            storageQuotaService.invalidateAllCaches(tenantId);
            return new StorageInfo(storageId, totalSize, createdAt, tenantId, path);
        } catch (IOException e) {
            log.error("Failed to store blob: path={}", path, e);
            throw new IllegalStateException("Failed to store blob", e);
        }
    }

    @Transactional
    StorageInfo store(String storageId, String tenantId, String path, InputStream stream) {
        Date createdAt = new Date();
        try (ChunkedOutputStream out =
                new ChunkedOutputStream(storageDataRepository, storageId, tenantId, path, chunkSize, createdAt)) {
            stream.transferTo(out);
            long totalSize = out.getTotalBytesWritten();
            log.debug("Stored blob: storageId={} tenant={} path={} size={}", storageId, tenantId, path, totalSize);
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
        long oldSize = oldFinal.getSize();

        StorageInfo newInfo = storeWithQuota(
                UUID.randomUUID().toString(),
                tenantId == null ? "" : tenantId,
                path == null ? "" : path,
                stream,
                -1L,
                oldSize);
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

        // Hard-delete the old chunks so the aggregation already excludes
        // them by the time the new write's pre-check runs. Pass oldSize=-1
        // because the bytes are no longer counted in storage_data.
        List<StorageData> toDrop = storageDataRepository.findAllByUuid(uuid);
        toDrop.forEach(storageDataRepository::delete);

        return storeWithQuota(uuid, tenantId == null ? "" : tenantId, path == null ? "" : path, stream, -1L, -1L);
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
                log.info("Deleting duplicate final chunk id={} createdAt={}", dup.getId(), dup.getCreatedAt());
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
            StorageInfo copy = store(targetTenantId, sourceInfo.path() == null ? "" : sourceInfo.path(), sourceStream);
            log.debug(
                    "Duplicated storage: sourceId={} targetId={} targetTenant={}",
                    sourceStorageId,
                    copy.id(),
                    targetTenantId);
            return copy.id();
        } finally {
            try {
                sourceStream.close();
            } catch (IOException e) {
                log.warn("Error closing source stream", e);
            }
        }
    }

    @Override
    public void forEachFinalStorageIdOlderThan(Instant cutoff, int batchSize, Consumer<List<String>> batchHandler) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be > 0");
        Query q = new Query(Criteria.where("isFinal").is(true).and("createdAt").lt(Date.from(cutoff)));
        q.fields().include("uuid");
        List<String> batch = new ArrayList<>(batchSize);
        try (Stream<StorageData> stream = mongoTemplate.stream(q, StorageData.class)) {
            for (StorageData chunk : (Iterable<StorageData>) stream::iterator) {
                String uuid = chunk.getUuid();
                if (uuid == null || uuid.isBlank()) continue;
                batch.add(uuid);
                if (batch.size() >= batchSize) {
                    batchHandler.accept(batch);
                    batch = new ArrayList<>(batchSize);
                }
            }
        }
        if (!batch.isEmpty()) {
            batchHandler.accept(batch);
        }
    }
}
