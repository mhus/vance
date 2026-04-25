package de.mhus.vance.brain.project;

import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Brain-side façade for project lifecycle and pod-affinity. Sessions
 * never claim a pod themselves — they go through the manager so the
 * "which pod owns this project" decision lives in one place.
 *
 * <p>v1 is single-pod: every claim succeeds against the local pod.
 * The seam is in place so multi-pod orchestration (refusing claims
 * for projects owned by another live pod, transferring ownership,
 * stale-takeover) can be added without touching the call sites.
 *
 * <p>Workspace and exec cleanup on archive land here too — the manager
 * delegates to the relevant services. v1 only scaffolds the call,
 * cleanup paths come once {@code archive} actually fires.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectManagerService {

    private final ProjectService projectService;
    private final LocationService locationService;

    /**
     * Ensures the project is owned by this pod. PENDING → ACTIVE on first
     * claim; ACTIVE on this pod refreshes the claim; ACTIVE on another pod
     * is taken over (logged at warn). Throws on ARCHIVED or unknown.
     */
    public ProjectDocument claimForLocalPod(String tenantId, String projectName) {
        String podIp = locationService.getPodIp();
        ProjectDocument doc = projectService.claim(tenantId, projectName, podIp);
        log.debug("Project '{}/{}' claimed for pod '{}'", tenantId, projectName, podIp);
        return doc;
    }

    /**
     * Asserts that {@code project} is owned by the local pod. Used by
     * call sites that want to surface a clear error before doing
     * project-scoped work without going through {@link #claimForLocalPod}.
     */
    public void requireOwnedByLocalPod(ProjectDocument project) {
        String podIp = locationService.getPodIp();
        if (!Objects.equals(podIp, project.getPodIp())) {
            throw new ProjectNotOwnedException(
                    "Project '" + project.getName()
                            + "' is owned by pod '" + project.getPodIp()
                            + "', not this pod ('" + podIp + "')");
        }
    }

    /** All projects this pod currently owns — for startup reclaim. */
    public List<ProjectDocument> projectsOwnedByLocalPod() {
        return projectService.findActiveByPod(locationService.getPodIp());
    }

    public static class ProjectNotOwnedException extends RuntimeException {
        public ProjectNotOwnedException(String message) {
            super(message);
        }
    }
}
