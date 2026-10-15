package de.mhus.vance.brain.lease;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import de.mhus.vance.shared.lease.LeaseRevocationListener;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Routes every reaped holder into the {@code podBound} matrix (§6 of
 * {@code specification/public/lease-system.md}) — for the scheduled
 * sweep ({@link ResourceLeaseSweeper}) and for the lazy idle reap inside
 * {@code ResourceLeaseService.acquire} ({@link #onReaped}):
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
 * <p>"Pod alive" is {@link LeasePodLiveness} — the criterion of project
 * ownership ({@link ClusterService#resolveEndpointByPodId}), and this pod is
 * always alive. The sweeper and the operator view use the same method; there
 * must not be two opinions about whether a pod lives.
 */
@Component
@Slf4j
public class LeaseRevocationDispatcher implements LeaseRevocationListener {

    private final LeaseHandlerRegistry registry;
    private final ClusterService cluster;
    private final HttpLeaseRevokedClient revokedClient;

    public LeaseRevocationDispatcher(
            LeaseHandlerRegistry registry, ClusterService cluster, HttpLeaseRevokedClient revokedClient) {
        this.registry = registry;
        this.cluster = cluster;
        this.revokedClient = revokedClient;
    }

    /**
     * Lazy-reap path: the acquiring thread must not wait for handler work
     * or a pod-to-pod POST, so the dispatch runs on its own virtual thread.
     */
    @Override
    public void onReaped(List<LeaseHolderInfo> holders) {
        List<LeaseHolderInfo> snapshot = List.copyOf(holders);
        Thread.ofVirtual().name("lease-revoke").start(() -> {
            try {
                dispatch(snapshot);
            } catch (RuntimeException e) {
                log.warn(
                        "lease revoke: dispatch of {} lazily reaped holder(s) failed: {}",
                        snapshot.size(),
                        e.toString());
            }
        });
    }

    /** Synchronous dispatch — the sweeper's path. Per-holder failures are contained. */
    public void dispatch(List<LeaseHolderInfo> holders) {
        String selfPod = cluster.selfPodId();
        for (LeaseHolderInfo info : holders) {
            if (info.pod().equals(selfPod)) {
                // This pod is alive by definition — its own endpoint row may lag.
                registry.dispatch(info);
                continue;
            }
            String endpoint = LeasePodLiveness.isAlive(cluster, info.pod())
                    ? cluster.resolveEndpointByPodId(info.pod()).orElse(null)
                    : null;
            if (endpoint != null) {
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
