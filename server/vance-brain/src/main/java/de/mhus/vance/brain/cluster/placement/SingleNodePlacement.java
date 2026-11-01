package de.mhus.vance.brain.cluster.placement;

import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Placement of the single-node edition: every project runs on this pod.
 *
 * <p>No labels, no capacity, no other pod to ask — so no
 * {@link ClusterFullException} either. A project's {@code placementSelector}
 * is a cloud notion and is ignored here.
 *
 * <p>A plain component; the cloud edition's {@code @Primary} implementation
 * replaces it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SingleNodePlacement implements ProjectPlacement {

    private final ProjectService projectService;
    /**
     * A provider to keep the bean graph acyclic: the lifecycle service itself
     * places projects through this interface.
     */
    private final ObjectProvider<ProjectLifecycleService> lifecycleServiceProvider;

    @Override
    public PlacementDecision place(ProjectDocument project, PlacementTrigger trigger) {
        if (ProjectService.isHomeless(project)) {
            throw new IllegalArgumentException("Project '" + project.getTenantId() + "/" + project.getName()
                    + "' is homeless — it is never placed");
        }
        lifecycleServiceProvider.getObject().bring(project.getTenantId(), project.getName());
        log.info("Placed '{}/{}' locally (single-node edition)", project.getTenantId(), project.getName());
        return new PlacementDecision.Here();
    }

    @Override
    public boolean isEligibleHere(ProjectDocument project) {
        return !ProjectService.isHomeless(project);
    }

    @Override
    public boolean isEligibleHere(String tenantId, String projectName) {
        return projectService
                .findByTenantAndName(tenantId, projectName)
                .map(this::isEligibleHere)
                .orElse(true);
    }

    @Override
    public int localHeadroom() {
        return Integer.MAX_VALUE;
    }
}
