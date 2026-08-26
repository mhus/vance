package de.mhus.vance.brain.project;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.cluster.placement.PlacementTrigger;
import de.mhus.vance.brain.cluster.placement.ProjectPlacementService;
import de.mhus.vance.shared.project.LifecycleType;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectOwnership;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Look up "where does this project live right now" and optionally trigger
 * a bring when the project is offline. The single entry point for callers
 * that need to route to a project's owning pod — see
 * {@code specification/cluster-project-management.md} §6.
 *
 * <p>Three flavours:
 * <ul>
 *   <li>HOMELESS — returns {@link Location#endpoint()} {@code empty}; the
 *       caller knows to handle the project pod-locally (the existing
 *       podless paths).</li>
 *   <li>Valid lease — resolves and returns the holder's endpoint.</li>
 *   <li>Expired or absent lease — depending on
 *       {@code autoStart}: a blocking placement through
 *       {@code ProjectPlacementService} or just-tell-me
 *       ({@code endpoint=empty}).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectLocator {

    public record Location(
            String tenantId,
            String projectName,
            Optional<String> endpoint,
            LifecycleType lifecycleType,
            ProjectStatus status,
            @Nullable String homeNode) {}

    private final ProjectService projectService;
    private final ClusterService clusterService;
    /**
     * The {@code vance.cluster.locator.autoStartTimeout} is enforced by the
     * HTTP read-timeout of {@code HttpClusterBringClient} inside the dispatch
     * — v1 has no extra wrapper around it.
     */
    private final ProjectPlacementService placementService;

    /**
     * Resolves the project's current owning endpoint. With
     * {@code autoStart=true} the call blocks until the project is brought
     * online (locally or via the master); without it the call returns
     * immediately and the caller decides what to do when the project is
     * offline.
     *
     * @throws ProjectService.ProjectNotFoundException unknown project
     * @throws de.mhus.vance.brain.cluster.placement.ClusterFullException
     *     no pod can take the project
     * @throws de.mhus.vance.brain.cluster.ClusterBringClient.ClusterBringException
     *     the bring itself failed
     */
    public Location locate(String tenantId, String projectName, boolean autoStart) {
        ProjectDocument project = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '" + tenantId + "'"));

        if (project.getLifecycleType() == LifecycleType.HOMELESS) {
            return new Location(tenantId, projectName, Optional.empty(),
                    LifecycleType.HOMELESS, project.getStatus(), null);
        }

        // Live home-node — resolve and return.
        Optional<String> liveEndpoint = liveEndpointOf(project);
        if (liveEndpoint.isPresent()) {
            return new Location(tenantId, projectName, liveEndpoint,
                    project.getLifecycleType(), project.getStatus(), project.getHomeNode());
        }

        if (!autoStart) {
            return new Location(tenantId, projectName, Optional.empty(),
                    project.getLifecycleType(), project.getStatus(), project.getHomeNode());
        }

        // autoStart=true — place it and wait for the bring to finish.
        placementService.place(project, PlacementTrigger.LOCATE);
        ProjectDocument fresh = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' vanished during autoStart"));
        Optional<String> endpoint = liveEndpointOf(fresh);
        return new Location(tenantId, projectName, endpoint,
                fresh.getLifecycleType(), fresh.getStatus(), fresh.getHomeNode());
    }

    private Optional<String> liveEndpointOf(ProjectDocument project) {
        return ProjectOwnership
                .liveOwnerPodId(project, Instant.now(), clusterService.leaseTtl())
                .flatMap(clusterService::resolveEndpointByPodId);
    }
}
