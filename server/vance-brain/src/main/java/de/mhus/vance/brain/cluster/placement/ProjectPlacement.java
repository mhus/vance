package de.mhus.vance.brain.cluster.placement;

import de.mhus.vance.shared.project.ProjectDocument;

/**
 * Where a project runs — the part of placement the kernel needs in every
 * edition.
 *
 * <p>The single-node edition ({@link SingleNodePlacement}) always answers
 * "here". The cloud edition decides across the pods of the cluster by labels
 * and load and dispatches the bring to the chosen pod. Callers are written
 * against this interface and cannot tell which one they got.
 *
 * <p>Placement is not ownership: an implementation decides and dispatches, it
 * never writes the lease. The claim in {@code ProjectService.claim} stays the
 * last word.
 */
public interface ProjectPlacement {

    /**
     * Decide where {@code project} runs and bring it there.
     *
     * @return the decision that was dispatched, never
     *         {@link PlacementDecision.Unschedulable}
     * @throws ClusterFullException when nothing can take the project
     * @throws IllegalArgumentException for the homeless tenant project, which is never placed
     */
    PlacementDecision place(ProjectDocument project, PlacementTrigger trigger);

    /**
     * Whether this pod may serve {@code project}. Capacity is not part of this
     * question — the attach paths that ask it have a user waiting. {@code false}
     * for the homeless tenant project, which runs nowhere.
     */
    boolean isEligibleHere(ProjectDocument project);

    /**
     * By name, for callers that hold a project id rather than the document. An
     * unknown project answers {@code true}: there is nothing to refuse, and the
     * caller's own not-found path is the place to report it.
     */
    boolean isEligibleHere(String tenantId, String projectName);

    /**
     * Score units this pod could still take before it hits its own cap. May be
     * negative when overbooked; {@link Integer#MAX_VALUE} when there is no cap.
     */
    int localHeadroom();
}
