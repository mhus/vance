package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.document.OrphanArchiveCleanupService;
import de.mhus.vance.shared.storage.StorageOrphanCleanupService;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide periodic orphan-cleanup driver. Runs on every pod but no-ops
 * unless the local pod currently holds the Cluster-Master lease — same
 * pattern as {@link ClusterCleanupTick} and {@link SessionStaleBindSweepTick}.
 *
 * <p>The actual sweep logic lives in {@link StorageOrphanCleanupService};
 * this class only handles scheduling + master-gating + config plumbing.
 *
 * <p>Defaults are conservative (PT1H interval, PT5M initial delay, PT1H
 * grace) — orphan blobs are not a correctness issue, just disk waste, so
 * we'd rather sweep slowly than risk over-deleting an in-flight write.
 * The grace period matches the assumption that any single document
 * create/update completes well within an hour.
 */
@Component
@ConditionalOnProperty(name = "vance.cluster.master.enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OrphanStorageSweepTick {

    private final ClusterMasterService masterService;
    private final StorageOrphanCleanupService cleanupService;
    private final OrphanArchiveCleanupService archiveCleanupService;

    @Value("${vance.storage.orphanSweep.gracePeriod:PT1H}")
    private Duration gracePeriod = Duration.ofHours(1);

    @Value("${vance.storage.orphanSweep.batchSize:500}")
    private int batchSize = 500;

    @Scheduled(fixedDelayString = "${vance.storage.orphanSweep.interval:PT1H}",
            initialDelayString = "${vance.storage.orphanSweep.initialDelay:PT5M}")
    public void tick() {
        if (!masterService.isLocalPodMaster()) {
            return;
        }
        try {
            // Each phase guards itself; this catches anything outside them so a
            // bad round never takes the scheduler thread with it.
            CleanupResult result = sweep(Instant.now());
            if (!result.isClean()) {
                log.info("OrphanStorageSweepTick: removed {} orphan archive(s) and "
                                + "{} orphan blob(s)",
                        result.orphanArchivesDeleted(), result.orphanStorageDeleted());
            }
        } catch (RuntimeException e) {
            log.warn("OrphanStorageSweepTick: sweep failed: {}", e.toString());
        }
    }

    /**
     * Pure sweep — extracted so tests can drive it deterministically.
     *
     * <p>Two sweeps, in order: archives whose lineage is gone, then blobs
     * nobody points at. Archives first, because deleting one can release
     * the last claim on a blob and the blob sweep should see that in the
     * same run rather than an hour later.
     *
     * <p>The order is an optimisation, not a dependency, so the phases are
     * guarded separately: a Mongo hiccup while walking the archives used to
     * skip the blob phase for a whole hour, and the log said only "sweep
     * failed". A failed phase reports zero and the other one still runs.
     */
    CleanupResult sweep(Instant now) {
        long archives = 0;
        try {
            archives = archiveCleanupService.sweepOnce(batchSize);
        } catch (RuntimeException e) {
            log.warn("OrphanStorageSweepTick: archive phase failed: {}", e.toString());
        }
        long blobs = 0;
        try {
            blobs = cleanupService.sweepOnce(now, gracePeriod, batchSize);
        } catch (RuntimeException e) {
            log.warn("OrphanStorageSweepTick: blob phase failed: {}", e.toString());
        }
        return new CleanupResult(archives, blobs);
    }

    /** What one run removed. */
    public record CleanupResult(long orphanArchivesDeleted, long orphanStorageDeleted) {
        public boolean isClean() {
            return orphanArchivesDeleted == 0 && orphanStorageDeleted == 0;
        }
    }
}
