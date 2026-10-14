package de.mhus.vance.brain.lease;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import de.mhus.vance.shared.lease.ResourceLeaseHolderDocument;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The brain-side reaper round (§6 of {@code planning/lease-system.md}):
 * idle reap, dead-pod reap and counter reconcile run in the shared
 * {@link ResourceLeaseService}; this component drives them and routes
 * every reaped holder into the {@code podBound} matrix (§8):
 *
 * <ul>
 *   <li>holder pod alive (this one) → local handler dispatch</li>
 *   <li>holder pod alive (other) → internal POST; unreachable falls
 *       through to the dead-pod path</li>
 *   <li>holder pod dead + {@code podBound} → nothing left to clean, the
 *       resource died with the pod</li>
 *   <li>holder pod dead + not {@code podBound} → handler runs on this
 *       pod with the holder's payload (e.g. DELETE the remote session)</li>
 * </ul>
 *
 * <p>"Pod alive" uses the same criterion as project ownership —
 * {@link ClusterService#resolveEndpointByPodId}: a pod is alive when its
 * {@code brain_pods} row is neither stopped nor stale by the pod-liveness
 * window. There must not be two opinions about whether a pod lives.
 */
@Component
@Slf4j
public class ResourceLeaseSweeper {

    private final ResourceLeaseService leaseService;
    private final LeaseHandlerRegistry registry;
    private final ClusterService cluster;
    private final HttpLeaseRevokedClient revokedClient;

    /** Counters quiet for this window may be reconciled — must comfortably exceed every waitMs. */
    @Value("${vance.lease.reconcile-safety-ms:60000}")
    private long reconcileSafetyMs;

    public ResourceLeaseSweeper(
            ResourceLeaseService leaseService,
            LeaseHandlerRegistry registry,
            ClusterService cluster,
            HttpLeaseRevokedClient revokedClient) {
        this.leaseService = leaseService;
        this.registry = registry;
        this.cluster = cluster;
        this.revokedClient = revokedClient;
    }

    @Scheduled(
            fixedDelayString = "${vance.lease.reap-interval:PT30S}",
            initialDelayString = "${vance.lease.reap-interval:PT30S}")
    public void sweep() {
        try {
            sweepRound();
        } catch (RuntimeException e) {
            // A failed round is survivable — the lazy idle sweep at
            // acquire and the TTL of the holders cover the gap.
            log.warn("resource lease sweep failed: {}", e.toString());
        }
    }

    /** Extracted so tests can drive a round deterministically. */
    void sweepRound() {
        dispatch(leaseService.reapIdle(null));
        reapDeadPods();
        leaseService.reconcile(Instant.now().minus(Duration.ofMillis(reconcileSafetyMs)));
    }

    private void reapDeadPods() {
        String selfPod = cluster.selfPodId();
        Set<String> pods = new HashSet<>();
        for (ResourceLeaseHolderDocument holder : leaseService.listHolders()) {
            pods.add(holder.getPod());
        }
        for (String pod : pods) {
            if (pod.equals(selfPod) || cluster.resolveEndpointByPodId(pod).isPresent()) {
                continue;
            }
            dispatch(leaseService.reapPod(pod));
        }
    }

    private void dispatch(List<ResourceLeaseHolderDocument> holders) {
        String selfPod = cluster.selfPodId();
        for (ResourceLeaseHolderDocument holder : holders) {
            LeaseHolderInfo info = leaseService.toInfo(holder);
            String endpoint = cluster.resolveEndpointByPodId(info.pod()).orElse(null);
            if (endpoint != null) {
                if (info.pod().equals(selfPod)) {
                    registry.dispatch(info);
                    continue;
                }
                try {
                    revokedClient.post(endpoint, info);
                } catch (RuntimeException e) {
                    log.warn(
                            "lease revoke: forwarding to holder pod '{}' failed ({}): {}",
                            info.pod(),
                            info.token(),
                            e.toString());
                    if (info.podBound()) continue;
                    registry.dispatch(info);
                }
            } else {
                // Dead pod: podBound resources died with it; the rest is
                // cleaned up here with the holder's payload.
                if (info.podBound()) {
                    log.info(
                            "lease revoke: holder pod '{}' is dead and resource is pod-bound — nothing to clean: {}",
                            info.pod(),
                            info.token());
                    continue;
                }
                registry.dispatch(info);
            }
        }
    }
}
