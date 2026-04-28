package de.mhus.vance.shared.storage;

import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes {@link StorageDelete} markers whose {@code deletedAt} has passed
 * and physically removes the associated chunks.
 *
 * <p>Disabled by default — opt-in via {@code vance.services.storage-cleanup=true}
 * in the app that embeds {@code vance-shared}. Only one instance in the cluster
 * should have this flag enabled.
 *
 * <p>The interval is controlled by {@code vance.storage.cleanup-interval-ms}
 * (default 15 minutes).
 */
@Component
@ConditionalOnProperty(value = "vance.services.storage-cleanup", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class StorageCleanupScheduler {

    private final StorageDataRepository storageDataRepository;
    private final StorageDeleteRepository storageDeleteRepository;

    @Scheduled(fixedDelayString = "#{${vance.storage.cleanup-interval-ms:900000}}")
    @Transactional
    public void cleanupDeletedStorage() {
        log.debug("Starting storage cleanup task");
        try {
            List<StorageDelete> toDelete = storageDeleteRepository
                    .findByDeletedAtLessThanEqual(new Date());
            if (toDelete.isEmpty()) {
                // log.debug("No storage deletions scheduled");
                return;
            }
            int deleted = 0;
            int errors = 0;
            for (StorageDelete entry : toDelete) {
                try {
                    String storageId = entry.getStorageId();
                    long chunkCount = storageDataRepository.countByUuid(storageId);
                    storageDataRepository.deleteByUuid(storageId);
                    storageDeleteRepository.delete(entry);
                    deleted++;
                    log.debug("Deleted storage: storageId={} chunks={}", storageId, chunkCount);
                } catch (Exception e) {
                    errors++;
                    log.error("Error deleting storage entry id={}", entry.getId(), e);
                }
            }
            log.info("Storage cleanup completed: deleted={} errors={} total={}",
                    deleted, errors, toDelete.size());
        } catch (Exception e) {
            log.error("Error during storage cleanup task", e);
        }
    }
}
