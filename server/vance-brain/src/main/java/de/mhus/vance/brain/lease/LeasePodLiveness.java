package de.mhus.vance.brain.lease;

import de.mhus.vance.brain.cluster.ClusterService;

/**
 * The one answer to "does the holder pod of a lease live?" — shared by the
 * sweeper (dead-pod reap), the {@link LeaseRevocationDispatcher} (which side
 * cleans up) and the operator view ({@link LeaseAdminService}). There must not
 * be two opinions: a view that calls a pod alive while the dispatcher treats
 * it as dead would show a lease the reap then handles as orphaned.
 *
 * <p>The criterion is project ownership's —
 * {@link ClusterService#resolveEndpointByPodId} (not stopped, not stale, has an
 * endpoint) — with one rule on top: <b>this pod is always alive</b>. It is
 * evidently running the check; a missing or lagging heartbeat row of its own
 * must not make it reap its own leases or skip the local handler of a
 * pod-bound resource that is still running here.
 */
final class LeasePodLiveness {

    private LeasePodLiveness() {}

    static boolean isAlive(ClusterService cluster, String podId) {
        return podId.equals(cluster.selfPodId())
                || cluster.resolveEndpointByPodId(podId).isPresent();
    }
}
