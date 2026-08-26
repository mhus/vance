package de.mhus.vance.brain.cluster.placement;

/**
 * Why a project could not be placed. Two values, because they are two
 * different actions for whoever provides pods — and telling them apart is
 * the whole point of reporting the failure at all
 * ({@code planning/project-placement-labels.md} §6.1).
 *
 * <p><b>A failed dispatch is neither of these.</b> When a pod with room was
 * chosen and the {@code bring} call to it failed, the decision succeeded and
 * the execution did not. That is an incident, not demand — a new pod does not
 * fix it — so it stays in the log and never becomes a {@code PlacementGap}.
 */
public enum PlacementGap {

    /**
     * No live pod is eligible at all. Today that means the cluster has no
     * live pods; once selectors land it also means no pod carries the
     * required labels. The answer is a pod of a <em>different kind</em>.
     */
    NO_ELIGIBLE_POD,

    /**
     * Eligible pods exist, none has room for the project's
     * {@code homeResourceScore}. The answer is <em>more</em> of the kind
     * that already exists.
     */
    NO_CAPACITY
}
