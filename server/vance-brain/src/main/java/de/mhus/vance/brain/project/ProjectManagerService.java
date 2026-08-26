package de.mhus.vance.brain.project;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectOwnership;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Brain-side façade for project <b>ownership</b>: taking the lease, renewing
 * it by taking it again, and answering who holds it. Sessions never claim a
 * pod themselves — they go through the manager so that "who owns this project"
 * lives in one place.
 *
 * <p><b>Ownership, not placement.</b> "Which pod <em>should</em> run this"
 * belongs to {@code ProjectPlacementService}; this class only executes a
 * decision that has already been made, by taking the lease here. The
 * distinction used to be blurred: a {@code spawnNew} method on this service
 * carried its own local-first heuristic and its own copy of a capacity check
 * ({@code planning/project-placement-labels.md} §1), which is why
 * {@link #claimForLocalPod} reads like a decision and is not one.
 *
 * <p>Pod affinity is an <b>ownership lease</b>: {@code homePodId} names the
 * holding pod and {@code claimedAt} says when it last renewed. Whether a
 * lease still holds is answered by {@code ProjectOwnership} from the document
 * alone — no join against {@code brain_pods}, and nothing to clean up when a
 * pod dies, because an un-renewed lease expires by itself. Callers that need
 * an actual {@code host:port} resolve the holder's id through
 * {@link ClusterService#resolveEndpointByPodId(String)}. See
 * {@code planning/project-ownership-lease-design.md} §3 and
 * {@code specification/engine-message-routing.md} §2.
 *
 * <p>Claim semantics are CAS: it succeeds when the lease is unclaimed,
 * already ours, or expired. Two pods racing on a fresh project
 * deterministically pick one winner; the other gets
 * {@link Optional#empty()} and must redirect rather than steal.
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
    private final ClusterService clusterService;

    /**
     * Ensures this pod holds the project's lease. Refreshes
     * {@code homePodId} + {@code claimedAt} on the document; lifecycle
     * status is left untouched (transition runs via
     * {@code ProjectLifecycleService}). Throws {@link ClaimRejectedException}
     * when another pod holds a valid lease, and on CLOSED or unknown.
     *
     * <p>Podless system projects (see {@link ProjectService#isPodless})
     * are returned unchanged — they live on whichever pod the user's
     * WS lands on and never take a lease.
     *
     * <p><b>Owning is not running — do not use this as an entry point.</b>
     * This is the raw lease primitive: it makes the pod the owner and does
     * <em>not</em> put the project into {@link ProjectActivationRegistry}.
     * The activation-gated document listeners ({@code UrsaHookDocumentListener},
     * {@code UrsaSchedulerDocumentListener}) only refresh on the activating
     * pod, while {@code DocumentChangeRouter} serves the <em>writing</em> pod
     * regardless of ownership — so a project that is claimed but never brought
     * holds its hooks and schedulers here and runs none of them, silently and
     * without an error anywhere. Every path that reacts to a user wanting to
     * <em>use</em> the project (session create / resume / bootstrap, workspace
     * adopt) must therefore go through
     * {@link ProjectLifecycleService#bring(String, String)}, which claims
     * through this method and then activates. {@code bring} is idempotent and
     * short-circuits to a lease refresh once the project runs here.
     */
    public ProjectDocument claimForLocalPod(String tenantId, String projectName) {
        if (ProjectService.isPodless(projectName)) {
            return projectService.findByTenantAndName(tenantId, projectName)
                    .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                            "Project '" + projectName + "' not found in tenant '"
                                    + tenantId + "'"));
        }
        ProjectDocument doc = projectService.claim(
                        tenantId, projectName,
                        clusterService.selfPodId(), clusterService.selfNodeName(),
                        clusterService.selfEndpoint(), clusterService.leaseTtl())
                .orElseThrow(() -> {
                    String holder = projectService.findByTenantAndName(tenantId, projectName)
                            .map(ProjectDocument::getHomeNode)
                            .orElse("<gone>");
                    return new ClaimRejectedException(
                            "Project '" + tenantId + "/" + projectName
                                    + "' is leased by pod '" + holder
                                    + "', refusing to steal from this pod ('"
                                    + clusterService.selfNodeName() + "')");
                });
        log.debug("Project '{}/{}' leased by this pod", tenantId, projectName);
        return doc;
    }

    /** All RUNNING projects this pod currently holds a lease on. */
    public List<ProjectDocument> projectsOwnedByLocalPod() {
        return projectService.findRunningByHomePodId(clusterService.selfPodId());
    }

    /**
     * Returns the Brain-Endpoint (in {@code host:port} format) of the
     * Home Pod claiming the given project — that is, the brain process
     * where its sessions, processes, and workspace live.
     *
     * <p>Returns {@link Optional#empty()} if the project does not exist,
     * is podless ({@link ProjectService#isPodless}), or holds no valid
     * ownership lease — never claimed, or claimed by a pod that stopped
     * renewing (crashed, or restarted on a new host IP). Callers that need a
     * present endpoint should treat the empty case as "lives wherever the WS
     * lands" or "pending bootstrap" and either retry or surface a
     * {@code 409 Conflict} to the user.
     *
     * <p>The lease is what stops a session from becoming permanently
     * unreachable after its home pod dies. Before it, this method had to
     * filter on pod staleness by hand, because {@link
     * ClusterService#resolveEndpoint} happily returned the dead pod's
     * {@code host:port} — it only checked the row exists, not its heartbeat —
     * so every {@code session-resume} was tunnelled to a host that no longer
     * answered (observed 2026-07-01). An expired lease now removes the holder
     * before an endpoint is ever looked up — and because the lease TTL is the
     * longer window, {@link ClusterService#resolveEndpointByPodId} keeps the
     * staleness gate as the second one (see {@code ClusterTimeWindows}).
     *
     * <p>This is the lookup primitive for engine-to-engine routing
     * (Eddie → Arthur via Working WS) and for workspace REST routing.
     * See {@code specification/engine-message-routing.md} §5.
     */
    public Optional<String> findProjectEndpoint(String tenantId, String projectName) {
        if (ProjectService.isPodless(projectName)) {
            return Optional.empty();
        }
        return projectService.findByTenantAndName(tenantId, projectName)
                .flatMap(project -> ProjectOwnership.liveOwnerPodId(
                        project, Instant.now(), clusterService.leaseTtl()))
                .flatMap(clusterService::resolveEndpointByPodId);
    }

    /**
     * Returns {@code true} if {@code endpoint} refers to this pod — used
     * by routing layers to decide between the local code path and a
     * Working WS / REST hop to another brain process.
     *
     * <p>Comparison is by exact-string match against the live
     * cluster-registry entry for this pod's node-name.
     */
    public boolean isLocalPod(String endpoint) {
        return clusterService.resolveEndpoint(clusterService.selfNodeName())
                .map(self -> Objects.equals(self, endpoint))
                .orElse(false);
    }

    /**
     * Atomic "claim if mine, otherwise tell the caller where the project lives".
     *
     * <p>Behaviour:
     * <ul>
     *   <li>Nobody holds a valid lease (fresh, never claimed, or the previous
     *       holder stopped renewing): the CAS in
     *       {@link ProjectService#claim} grants the lease to this pod
     *       and we return {@link ClaimResult.Local}.</li>
     *   <li>The lease is already ours: the CAS renews it and we return
     *       {@link ClaimResult.Local}.</li>
     *   <li>Another pod holds a valid lease: the CAS rejects. We resolve that
     *       pod's endpoint and return {@link ClaimResult.Redirect} so the
     *       caller can tunnel or reject.</li>
     * </ul>
     *
     * <p>One atomic CAS, no live-pod snapshot to assemble first. Two pods
     * racing on a fresh project pick one winner, never both.
     *
     * <p><b>A {@link ClaimResult.Local} answer is a lease, not a running
     * project.</b> Same rule as {@link #claimForLocalPod}: the caller must
     * follow up with {@link ProjectLifecycleService#bring(String, String)}
     * before treating the project as usable, or its hooks and schedulers stay
     * dark on the pod that owns them.
     */
    public ClaimResult claimForLocalPodOrRedirect(String tenantId, String projectName) {
        if (ProjectService.isPodless(projectName)) {
            // Podless system projects (e.g. _user_<login>, _vance) live
            // wherever the WS lands — never redirect, never take a lease.
            return new ClaimResult.Local(claimForLocalPod(tenantId, projectName));
        }
        Optional<ProjectDocument> claimed = projectService.claim(
                tenantId, projectName,
                clusterService.selfPodId(), clusterService.selfNodeName(),
                clusterService.selfEndpoint(), clusterService.leaseTtl());
        if (claimed.isPresent()) {
            return new ClaimResult.Local(claimed.get());
        }
        // CAS rejected — re-read to find the holder and resolve to an endpoint.
        ProjectDocument current = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' vanished between claim and redirect"));
        String holder = ProjectOwnership
                .liveOwnerPodId(current, Instant.now(), clusterService.leaseTtl())
                .orElseThrow(() -> new ClaimRejectedException(
                        "Project '" + tenantId + "/" + projectName
                                + "' claim rejected but no live lease holder;"
                                + " concurrent state change"));
        String endpoint = clusterService.resolveEndpointByPodId(holder)
                .orElseThrow(() -> new ClaimRejectedException(
                        "Project '" + tenantId + "/" + projectName
                                + "' is leased by pod '" + current.getHomeNode()
                                + "' but the cluster registry has no live endpoint for it "
                                + "(row purged, stopped, or not beating)"));
        return new ClaimResult.Redirect(endpoint);
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

    /**
     * Thrown by {@link #claimForLocalPod} when the CAS rejects the claim
     * because another pod holds a valid lease.
     */
    public static class ClaimRejectedException extends RuntimeException {
        public ClaimRejectedException(String message) {
            super(message);
        }
    }
}
