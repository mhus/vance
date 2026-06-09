package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic hard-delete of {@code brain_pods} rows whose
 * {@code lastHeartbeatAt} is older than
 * {@link ClusterProperties.Cleanup#getAfter()}. Runs on every pod but
 * no-ops unless the local pod currently holds the Cluster-Master lease
 * — same pattern as {@link ClusterDistributorTick}.
 *
 * <p>Without this sweep, every pod restart leaves a {@code STOPPED}
 * row behind, and crashed pods leave behind {@code RUNNING} rows with
 * an old heartbeat. Both accumulate forever — production Mongo on this
 * project reached 238 rows in ~3 weeks before this tick existed.
 *
 * <p>Self-protection: the local pod's own row is never deleted, even if
 * its heartbeat looks old (clock skew, paused JVM during debugging).
 * Status is not inspected — the heartbeat age is the single source of
 * truth.
 */
@Component
@ConditionalOnProperty(name = "vance.cluster.master.enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ClusterCleanupTick {

    private final ClusterMasterService masterService;
    private final ClusterService clusterService;
    private final ClusterProperties properties;
    private final BrainPodService brainPodService;

    @Scheduled(fixedDelayString = "${vance.cluster.cleanup.interval:PT10M}",
            initialDelayString = "${vance.cluster.cleanup.initialDelay:PT2M}")
    public void tick() {
        if (!masterService.isLocalPodMaster()) {
            return;
        }
        try {
            sweep(Instant.now());
        } catch (RuntimeException e) {
            log.warn("ClusterCleanupTick: sweep failed: {}", e.toString());
        }
    }

    /**
     * Pure sweep — extracted so tests can drive it deterministically.
     * Returns the number of rows deleted.
     */
    int sweep(Instant now) {
        Duration after = properties.getCleanup().getAfter();
        Instant cutoff = now.minus(after);
        String clusterId = clusterService.selfClusterId();
        String selfPodId = clusterService.selfPodId();

        List<BrainPodDocument> all = brainPodService.listCluster(clusterId);
        int deleted = 0;
        for (BrainPodDocument doc : all) {
            if (selfPodId != null && selfPodId.equals(doc.getPodId())) {
                continue;
            }
            Instant beat = doc.getLastHeartbeatAt();
            if (beat == null) {
                continue;
            }
            if (beat.isBefore(cutoff)) {
                try {
                    long removed = brainPodService.deleteByPodId(doc.getPodId());
                    if (removed > 0) {
                        deleted++;
                        log.info("ClusterCleanupTick: removed stale pod '{}' (lastHeartbeatAt={})",
                                doc.getNodeName(), beat);
                    }
                } catch (RuntimeException e) {
                    log.warn("ClusterCleanupTick: delete failed for pod '{}': {}",
                            doc.getNodeName(), e.toString());
                }
            }
        }
        if (deleted > 0) {
            log.info("ClusterCleanupTick: swept {} stale pod row(s) older than {}",
                    deleted, after);
        }
        return deleted;
    }
}
