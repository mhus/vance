package de.mhus.vance.brain.cluster.placement;

import lombok.Getter;

/**
 * No pod can take the project. Carries the {@link PlacementGap} so callers
 * that report upwards do not have to re-derive it from a message string.
 *
 * <p>Lived as a nested class on {@code ClusterMasterService} before, from a
 * time when only the master decided placement. It is not a master concern —
 * any pod can run out of places to put a project.
 *
 * <p>Thrown by {@link ProjectPlacementService#place}, which is the
 * fail-fast surface. Phase 5 of
 * {@code planning/project-placement-labels.md} replaces the throw on the
 * create path with a returned "pending placement" state, because a project
 * waiting for a pod that is being provisioned is not an error.
 */
@Getter
public class ClusterFullException extends RuntimeException {

    private final PlacementGap gap;

    public ClusterFullException(PlacementGap gap, String message) {
        super(message);
        this.gap = gap;
    }
}
