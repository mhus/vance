package de.mhus.vance.brain.cluster;

import de.mhus.vance.shared.cluster.ClusterMasterDocument;
import java.util.Optional;

/**
 * Answers "may this pod run the work that only one pod in the cluster may
 * run?" — the periodic housekeeping sweeps, model discovery, the upstream
 * tracker transfer.
 *
 * <p>The kernel only <em>asks</em>. How the answer comes about is edition
 * specific: the single-node edition ({@link SingleNodeCoordinator}) is always
 * the coordinator, the cloud edition elects one pod through a rotating lease
 * in Mongo. Keeping the question in the kernel is what lets a community brain
 * run every one of those jobs without the election machinery being present.
 */
public interface ClusterCoordinator {

    /** {@code true} when this pod is the one that runs coordinator-only work. */
    boolean isLocalPodMaster();

    /**
     * {@code false} when nobody coordinates because the election is switched
     * off ({@code vance.cluster.master.enabled=false} in the cloud edition).
     *
     * <p>Callers tell the two "not me" cases apart with this: an idempotent
     * internal sweep then runs on every pod (doing it twice costs nothing,
     * nobody doing it stalls work), while a write into a foreign system stays
     * off (a duplicate there cannot be taken back).
     */
    default boolean isCoordinated() {
        return true;
    }

    /** The current coordinator lease, for diagnostic endpoints. Empty when there is none. */
    Optional<ClusterMasterDocument> currentLease();

    /**
     * Whether an idempotent internal sweep may run on this pod.
     */
    default boolean runsIdempotentSweeps() {
        return !isCoordinated() || isLocalPodMaster();
    }

    /**
     * Whether work that writes into a foreign system may run on this pod.
     */
    default boolean runsForeignWrites() {
        return isCoordinated() && isLocalPodMaster();
    }
}
