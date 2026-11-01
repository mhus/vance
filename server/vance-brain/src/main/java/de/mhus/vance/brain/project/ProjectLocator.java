package de.mhus.vance.brain.project;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.cluster.placement.PlacementTrigger;
import de.mhus.vance.brain.cluster.placement.ProjectPlacement;
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
 * <p>Who is asking decides what may be started. A <b>machine</b> trigger (an event,
 * a webhook) only starts a project that wants to run; an idle or suspended one stays
 * down, and the trigger finds no endpoint. A <b>user</b> opening the project
 * ({@link #locateForUser}) wakes it from any state but archived.
 *
 * <p>Three flavours:
 * <ul>
 *   <li>HOMELESS — returns {@link Location#endpoint()} {@code empty}; the
 *       tenant project is never placed and nothing runs there, so there is
 *       nowhere to route to.</li>
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
    private final ProjectPlacement placementService;

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
        return locate(tenantId, projectName, autoStart ? StartMode.MACHINE : StartMode.NONE);
    }

    /**
     * A user opens the project: the wake-up path of a login. Like
     * {@code locate(..., true)} it places the project and waits for the bring — but
     * it starts a project from <em>any</em> state except archived, and it writes the
     * desired state {@code RUNNING} first. That order is deliberate: if no pod can
     * take the project right now, it stays marked as wanting to run and waiting
     * ({@code pendingSince}), so the distributor places it as soon as a pod
     * appears, instead of leaving an idle project nobody ever picks up.
     *
     * @throws ProjectService.ProjectClosedException the project is archived
     * @throws de.mhus.vance.brain.cluster.placement.ClusterFullException no pod can take it
     */
    public Location locateForUser(String tenantId, String projectName) {
        return locate(tenantId, projectName, StartMode.USER);
    }

    /** Who is asking for a start, which decides whether a stopped project may be started. */
    private enum StartMode {
        /** Look only. */
        NONE,
        /** An event or webhook: starts what wants to run, nothing else. */
        MACHINE,
        /** A user opened the project: starts everything but archived. */
        USER
    }

    private Location locate(String tenantId, String projectName, StartMode mode) {
        ProjectDocument project = projectService
                .findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '" + tenantId + "'"));

        if (ProjectService.isHomeless(project)) {
            return new Location(
                    tenantId, projectName, Optional.empty(), LifecycleType.HOMELESS, project.getStatus(), null);
        }

        // Live home-node — resolve and return.
        Optional<String> liveEndpoint = liveEndpointOf(project);
        if (liveEndpoint.isPresent()) {
            return new Location(
                    tenantId,
                    projectName,
                    liveEndpoint,
                    project.getLifecycleType(),
                    project.getStatus(),
                    project.getHomeNode());
        }

        if (mode == StartMode.NONE) {
            return notStarted(project);
        }
        if (mode == StartMode.MACHINE && !ProjectService.wantsToRun(project.getStatus())) {
            // An idle project is idle because its users stayed away, and a suspended
            // one because somebody stopped it. A webhook or an event must not undo
            // either — only a person opening the project does.
            log.debug(
                    "Project '{}/{}' is {} — a machine trigger does not start it",
                    tenantId,
                    projectName,
                    project.getStatus());
            return notStarted(project);
        }

        if (mode == StartMode.USER) {
            project = wantToRun(project);
        }
        // Placing it and waiting for the bring to finish.
        placementService.place(project, mode == StartMode.USER ? PlacementTrigger.WAKE : PlacementTrigger.LOCATE);
        ProjectDocument fresh = projectService
                .findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' vanished during autoStart"));
        Optional<String> endpoint = liveEndpointOf(fresh);
        return new Location(
                tenantId, projectName, endpoint, fresh.getLifecycleType(), fresh.getStatus(), fresh.getHomeNode());
    }

    private static Location notStarted(ProjectDocument project) {
        return new Location(
                project.getTenantId(),
                project.getName(),
                Optional.empty(),
                project.getLifecycleType(),
                project.getStatus(),
                project.getHomeNode());
    }

    /**
     * Moves an idle or suspended project to the desired state RUNNING before it is
     * placed. A strict compare-and-set from what was read: a racing close wins.
     */
    private ProjectDocument wantToRun(ProjectDocument project) {
        ProjectStatus status = project.getStatus();
        if (status == ProjectStatus.CLOSED) {
            throw new ProjectService.ProjectClosedException(
                    "Project '" + project.getName() + "' is archived — cannot start it");
        }
        if (status == ProjectStatus.RUNNING || status == ProjectStatus.INIT) {
            return project;
        }
        return projectService.transitionStatus(project.getTenantId(), project.getName(), status, ProjectStatus.RUNNING);
    }

    private Optional<String> liveEndpointOf(ProjectDocument project) {
        return ProjectOwnership.liveOwnerPodId(project, Instant.now(), clusterService.leaseTtl())
                .flatMap(clusterService::resolveEndpointByPodId);
    }
}
