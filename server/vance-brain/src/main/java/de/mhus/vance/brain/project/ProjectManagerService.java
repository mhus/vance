package de.mhus.vance.brain.project;

import de.mhus.vance.shared.location.LocationService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Brain-side façade for project lifecycle and pod-affinity. Sessions
 * never claim a pod themselves — they go through the manager so the
 * "which pod owns this project" decision lives in one place.
 *
 * <p>The pod-affinity field {@link ProjectDocument#getPodIp()} stores
 * a Brain-Endpoint in {@code host:port} format (IPv4: {@code 192.168.1.10:8080},
 * IPv6: {@code [fe80::1]:8080}). The field name remains {@code podIp}
 * for historical reasons. See {@code specification/execution-und-persistenz.md}
 * §3.1 and {@code specification/engine-message-routing.md} §2 for the
 * routing model that depends on this format.
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
     * Ensures the project is owned by this pod. Refreshes podIp + claimedAt
     * on the document; lifecycle status is left untouched (transition runs
     * via {@code ProjectLifecycleService}). Takes over from another pod
     * (logged at warn). Throws on CLOSED or unknown.
     */
    public ProjectDocument claimForLocalPod(String tenantId, String projectName) {
        String endpoint = locationService.getPodAddress();
        ProjectDocument doc = projectService.claim(tenantId, projectName, endpoint);
        log.debug("Project '{}/{}' claimed for pod '{}'", tenantId, projectName, endpoint);
        return doc;
    }

    /**
     * Asserts that {@code project} is owned by the local pod. Used by
     * call sites that want to surface a clear error before doing
     * project-scoped work without going through {@link #claimForLocalPod}.
     */
    public void requireOwnedByLocalPod(ProjectDocument project) {
        String endpoint = locationService.getPodAddress();
        if (!Objects.equals(endpoint, project.getPodIp())) {
            throw new ProjectNotOwnedException(
                    "Project '" + project.getName()
                            + "' is owned by pod '" + project.getPodIp()
                            + "', not this pod ('" + endpoint + "')");
        }
    }

    /** All RUNNING projects this pod currently owns — for startup reclaim. */
    public List<ProjectDocument> projectsOwnedByLocalPod() {
        return projectService.findRunningByPod(locationService.getPodAddress());
    }

    /**
     * Returns the Brain-Endpoint (in {@code host:port} format) of the
     * Home Pod claiming the given project — that is, the brain process
     * where its sessions, processes, and workspace live.
     *
     * <p>Returns {@link Optional#empty()} if the project does not exist
     * or has not yet been claimed by any pod. Callers that need a
     * present endpoint should treat the empty case as "pending bootstrap"
     * and either retry or surface a {@code 409 Conflict} to the user.
     *
     * <p>This is the lookup primitive for engine-to-engine routing
     * (Eddie → Arthur via Working WS) and for workspace REST routing.
     * See {@code specification/engine-message-routing.md} §5 and
     * §10 (the open point on caching strategy applies — v1 reads
     * Mongo every call; a cache layer may be added later).
     */
    public Optional<String> findProjectEndpoint(String tenantId, String projectName) {
        return projectService.findByTenantAndName(tenantId, projectName)
                .map(ProjectDocument::getPodIp)
                .filter(ip -> ip != null && !ip.isBlank());
    }

    /**
     * Returns {@code true} if {@code endpoint} refers to this pod —
     * used by routing layers to decide between the local code path and
     * a Working WS / REST hop to another brain process.
     *
     * <p>Comparison is by exact-string match against
     * {@link LocationService#getPodAddress()}.
     */
    public boolean isLocalPod(String endpoint) {
        return Objects.equals(endpoint, locationService.getPodAddress());
    }

    /**
     * Atomic "claim if mine, otherwise tell the caller where the project lives".
     *
     * <p>Behaviour:
     * <ul>
     *   <li>Project does not yet have a Home Pod (fresh / never claimed):
     *       claim for the local pod, return {@link ClaimResult.Local}.</li>
     *   <li>Project's Home Pod is this pod: refresh the claim, return
     *       {@link ClaimResult.Local}.</li>
     *   <li>Project's Home Pod is another brain process: do <em>not</em>
     *       steal the claim, return {@link ClaimResult.Redirect} with the
     *       owning endpoint so the caller can either tunnel or reject.</li>
     * </ul>
     *
     * <p>This replaces the previous "claim always wins" behaviour at the
     * connection-bind sites — a multi-pod cluster must not have sessions
     * cycle a project's Home Pod with every reconnect.
     *
     * @return {@link ClaimResult.Local} or {@link ClaimResult.Redirect};
     *     never {@code null}.
     */
    public ClaimResult claimForLocalPodOrRedirect(String tenantId, String projectName) {
        Optional<String> existing = findProjectEndpoint(tenantId, projectName);
        if (existing.isPresent() && !isLocalPod(existing.get())) {
            return new ClaimResult.Redirect(existing.get());
        }
        ProjectDocument claimed = claimForLocalPod(tenantId, projectName);
        return new ClaimResult.Local(claimed);
    }

    /**
     * Outcome of {@link #claimForLocalPodOrRedirect(String, String)}.
     */
    public sealed interface ClaimResult {
        /** The project is now (or already was) owned by this pod. */
        record Local(ProjectDocument doc) implements ClaimResult {}

        /**
         * The project lives on another brain process; the caller should
         * either open a Working WS to {@link #endpoint()} or surface a
         * routing error to the client.
         */
        record Redirect(String endpoint) implements ClaimResult {}
    }

    public static class ProjectNotOwnedException extends RuntimeException {
        public ProjectNotOwnedException(String message) {
            super(message);
        }
    }
}
