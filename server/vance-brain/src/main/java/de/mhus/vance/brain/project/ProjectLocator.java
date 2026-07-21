package de.mhus.vance.brain.project;

import de.mhus.vance.brain.cluster.ClusterBringClient;
import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.brain.cluster.ClusterPlacementService;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.project.LifecycleType;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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
 *   <li>Live {@code homeNode} — resolves and returns the endpoint.</li>
 *   <li>Stale {@code homeNode} / {@code null} — depending on
 *       {@code autoStart}: blocking bring (master if available, else
 *       local-direct) or just-tell-me ({@code endpoint=empty}).</li>
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
    private final ProjectLifecycleService lifecycleService;
    private final ClusterPlacementService placementService;
    private final ClusterBringClient bringClient;
    /**
     * Optional — only present when {@code vance.cluster.master.enabled=true}.
     * Used to short-circuit "I'm the master, I'll place this locally"
     * vs. "ask the master via REST". The {@code vance.cluster.locator.autoStartTimeout}
     * is enforced by the HTTP read-timeout of {@code HttpClusterBringClient}
     * — v1 has no extra wrapper around it.
     */
    private final ObjectProvider<ClusterMasterService> masterServiceProvider;

    /**
     * Resolves the project's current owning endpoint. With
     * {@code autoStart=true} the call blocks until the project is brought
     * online (locally or via the master); without it the call returns
     * immediately and the caller decides what to do when the project is
     * offline.
     *
     * @throws ProjectService.ProjectNotFoundException unknown project
     * @throws ClusterBringClient.ClusterBringException bring/spawn failed
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

        // autoStart=true — trigger a bring (local or via master).
        triggerBring(project);
        ProjectDocument fresh = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' vanished during autoStart"));
        Optional<String> endpoint = liveEndpointOf(fresh);
        return new Location(tenantId, projectName, endpoint,
                fresh.getLifecycleType(), fresh.getStatus(), fresh.getHomeNode());
    }

    /**
     * Decide between local-direct bring and master-routed spawn. Local
     * when (a) the master role is disabled, (b) this pod is the master,
     * or (c) this pod has room itself — saves a network hop for the
     * common single-pod-cluster case.
     */
    private void triggerBring(ProjectDocument project) {
        ClusterMasterService masterService = masterServiceProvider.getIfAvailable();
        boolean masterDisabled = masterService == null;
        boolean iAmMaster = masterService != null && masterService.isLocalPodMaster();

        if (masterDisabled || iAmMaster || haveLocalRoom(project)) {
            log.debug("ProjectLocator: autoStart bringing '{}/{}' locally (masterDisabled={}, iAmMaster={})",
                    project.getTenantId(), project.getName(), masterDisabled, iAmMaster);
            // placementService.placeProject handles "pick pod + dispatch"; when
            // I'm the master that's me. When master is disabled, the
            // ClusterFullException fallback below tries a pure local bring.
            if (iAmMaster) {
                placementService.placeProject(project);
                return;
            }
            if (masterDisabled) {
                lifecycleService.bring(project.getTenantId(), project.getName());
                return;
            }
            // I have room locally — direct local bring.
            lifecycleService.bring(project.getTenantId(), project.getName());
            return;
        }

        // Not master, no room locally — go through the master.
        Optional<String> masterEndpoint = masterService.resolveMasterEndpoint();
        if (masterEndpoint.isEmpty()) {
            log.warn("ProjectLocator: no master endpoint for '{}/{}', bringing locally as fallback",
                    project.getTenantId(), project.getName());
            lifecycleService.bring(project.getTenantId(), project.getName());
            return;
        }
        bringClient.requestSpawn(masterEndpoint.get(),
                project.getTenantId(), project.getName());
    }

    private Optional<String> liveEndpointOf(ProjectDocument project) {
        String homeNode = project.getHomeNode();
        if (homeNode == null || homeNode.isBlank()) return Optional.empty();
        return clusterService.resolveLiveEndpoint(homeNode);
    }

    private boolean haveLocalRoom(ProjectDocument project) {
        return clusterService.selfPod()
                .map(pod -> pod.getResourcesCurrentScore() + project.getHomeResourceScore()
                        <= pod.getResourcesMaxScore())
                .orElse(true);
    }
}
