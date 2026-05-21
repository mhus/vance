package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.cluster.ClusterMasterDocument;
import de.mhus.vance.shared.cluster.ClusterMasterStore;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Holds the rotating Cluster-Master lease and exposes "am I the master?"
 * to other brain services. See {@code specification/cluster-project-management.md}
 * §4.
 *
 * <p>One election tick per {@link ClusterProperties.Master#getElectionInterval()}
 * on every pod:
 * <ul>
 *   <li>If I'm master and the lease is about to expire (within {@code renewSafetyMargin}):
 *       renew via {@link ClusterMasterStore#renew}. A failed renew means
 *       someone stole the lease — I drop the role.</li>
 *   <li>If the slot is unclaimed or its lease has expired: try a steal via
 *       {@link ClusterMasterStore#tryAcquire}.</li>
 *   <li>Otherwise leave the healthy master alone.</li>
 * </ul>
 *
 * <p>Disabled cluster-wide via {@code vance.cluster.master.enabled=false} —
 * the bean is not even loaded then. Direct-spawn falls back to local-bring
 * when no master is reachable (see {@code ProjectManagerService.spawnNew}).
 */
@Service
@ConditionalOnProperty(name = "vance.cluster.master.enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ClusterMasterService {

    private final ClusterMasterStore store;
    private final ClusterService clusterService;
    private final ClusterProperties properties;

    /**
     * Cached "I currently hold the lease" flag — updated by the tick.
     * Read by the distributor and the REST controller to gate
     * master-only behaviour. May lag the Mongo state by up to one tick,
     * which is fine — every master-only action re-checks the lease via
     * {@link #isLocalPodMaster()} on call.
     */
    private volatile boolean localIsMaster = false;
    private volatile @org.jspecify.annotations.Nullable Instant localLeaseUntil = null;

    @Scheduled(fixedDelayString = "${vance.cluster.master.electionInterval:PT30S}",
            initialDelayString = "${vance.cluster.master.electionInitialDelay:PT5S}")
    public void electionTick() {
        try {
            tick(Instant.now());
        } catch (RuntimeException e) {
            log.warn("ClusterMasterService: election tick failed: {}", e.toString());
        }
    }

    /**
     * Pure tick — extracted so tests can drive it deterministically without
     * the scheduler. Returns the new local-master state.
     */
    public boolean tick(Instant now) {
        String clusterId = clusterService.selfClusterId();
        String selfPodId = clusterService.selfPodId();
        String selfNode = clusterService.selfNodeName();
        if (selfPodId == null || selfPodId.isBlank() || selfNode == null || selfNode.isBlank()) {
            // Cluster registration hasn't completed yet — wait for the next tick.
            return localIsMaster;
        }
        String selfEndpoint = clusterService.selfPod()
                .map(p -> p.getEndpoint())
                .orElse("");
        Duration leaseDuration = properties.getMaster().getLeaseDuration();
        Duration safetyMargin = properties.getMaster().getRenewSafetyMargin();
        Instant newLeaseUntil = now.plus(leaseDuration);

        Optional<ClusterMasterDocument> readOpt = store.find(clusterId);
        if (readOpt.isEmpty()) {
            // Initial election — try to insert.
            return acquire(clusterId, null, selfPodId, selfNode, selfEndpoint, now, newLeaseUntil);
        }
        ClusterMasterDocument read = readOpt.get();
        boolean iAmMaster = selfPodId.equals(read.getCurrentPodId());
        Instant leaseUntil = read.getLeaseUntil();

        if (iAmMaster) {
            if (leaseUntil == null || leaseUntil.isBefore(now.plus(safetyMargin))) {
                Optional<ClusterMasterDocument> renewed = store.renew(clusterId, selfPodId, newLeaseUntil);
                if (renewed.isPresent()) {
                    localIsMaster = true;
                    localLeaseUntil = newLeaseUntil;
                    return true;
                }
                // Renewal lost — someone took over.
                log.warn("ClusterMasterService: renewal lost for pod '{}', dropping master role", selfPodId);
                localIsMaster = false;
                localLeaseUntil = null;
                return false;
            }
            // Healthy lease, still mine.
            localIsMaster = true;
            localLeaseUntil = leaseUntil;
            return true;
        }

        // Not me — only act when the lease is empty or expired.
        if (leaseUntil == null || !leaseUntil.isAfter(now)) {
            return acquire(clusterId, read.getCurrentPodId(), selfPodId, selfNode, selfEndpoint,
                    now, newLeaseUntil);
        }

        // Healthy master that isn't me — do nothing.
        if (localIsMaster) {
            // We thought we were master but Mongo disagrees — sync local state.
            log.warn("ClusterMasterService: drift detected, '{}' holds the lease, dropping local master role",
                    read.getCurrentNodeName());
        }
        localIsMaster = false;
        localLeaseUntil = null;
        return false;
    }

    private boolean acquire(String clusterId, @org.jspecify.annotations.Nullable String expected,
                            String selfPodId, String selfNode, String selfEndpoint,
                            Instant now, Instant newLeaseUntil) {
        Optional<ClusterMasterDocument> acquired = store.tryAcquire(
                clusterId, expected, selfPodId, selfNode, selfEndpoint, now, newLeaseUntil);
        if (acquired.isPresent()) {
            boolean wasMaster = localIsMaster;
            localIsMaster = true;
            localLeaseUntil = newLeaseUntil;
            if (!wasMaster) {
                log.info("ClusterMasterService: pod '{}' acquired master role until {}",
                        selfNode, newLeaseUntil);
            }
            return true;
        }
        localIsMaster = false;
        localLeaseUntil = null;
        return false;
    }

    @PreDestroy
    void onShutdown() {
        if (!localIsMaster) return;
        String clusterId = clusterService.selfClusterId();
        String selfPodId = clusterService.selfPodId();
        try {
            boolean cleared = store.release(clusterId, selfPodId);
            if (cleared) {
                log.info("ClusterMasterService: released master lease on shutdown");
            }
        } catch (RuntimeException e) {
            log.warn("ClusterMasterService: shutdown release failed: {}", e.toString());
        }
    }

    /** {@code true} when the local pod currently holds the master lease. */
    public boolean isLocalPodMaster() {
        return localIsMaster;
    }

    /** Last observed lease-expiry — for diagnostic endpoints. */
    public Optional<Instant> localLeaseUntil() {
        return Optional.ofNullable(localLeaseUntil);
    }

    /**
     * Endpoint ({@code host:port}) of the current master, if any. Reads
     * Mongo every time — callers should cache briefly if calling in tight
     * loops.
     */
    public Optional<String> resolveMasterEndpoint() {
        return store.find(clusterService.selfClusterId())
                .map(ClusterMasterDocument::getCurrentEndpoint)
                .filter(e -> e != null && !e.isBlank());
    }

    /** Diagnostic accessor for the REST status endpoint. */
    public Optional<ClusterMasterDocument> currentLease() {
        return store.find(clusterService.selfClusterId());
    }

    /** Thrown by {@link #placeProject} when the local pod is not the master. */
    public static class NotMasterException extends RuntimeException {
        public NotMasterException(String message) {
            super(message);
        }
    }

    /** Thrown by {@link #placeProject} when no live pod has room. */
    public static class ClusterFullException extends RuntimeException {
        public ClusterFullException(String message) {
            super(message);
        }
    }
}
