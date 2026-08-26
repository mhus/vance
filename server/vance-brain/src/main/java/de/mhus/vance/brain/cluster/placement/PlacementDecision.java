package de.mhus.vance.brain.cluster.placement;

import de.mhus.vance.shared.cluster.BrainPodDocument;

/**
 * Where a project should run, as decided by {@link ProjectPlacementService}.
 *
 * <p>{@link Here} and {@link On} are not redundant. {@code Here} is the answer
 * when there is no pod document to point at — a podless project, or a pod that
 * has not finished registering itself in {@code brain_pods} yet (boot). Folding
 * it into {@code On(selfPod)} would mean the boot path had to invent a document
 * it does not have.
 */
public sealed interface PlacementDecision {

    /** Run it on this pod, whatever the cluster registry currently says. */
    record Here() implements PlacementDecision {}

    /**
     * Run it on {@code pod}. May well be this pod — the load comparison does
     * not know or care whose row it picked; {@link ProjectPlacementService}
     * resolves that when it dispatches.
     */
    record On(BrainPodDocument pod) implements PlacementDecision {}

    /** Nowhere to run it. See {@link PlacementGap}. */
    record Unschedulable(PlacementGap gap) implements PlacementDecision {}
}
