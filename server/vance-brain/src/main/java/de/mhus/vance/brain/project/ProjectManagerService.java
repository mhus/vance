package de.mhus.vance.brain.project;

import de.mhus.vance.brain.cluster.ClusterBringClient;
import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.LifecycleType;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Brain-side façade for project lifecycle and pod-affinity. Sessions
 * never claim a pod themselves — they go through the manager so the
 * "which pod owns this project" decision lives in one place.
 *
 * <p>The pod-affinity field {@link ProjectDocument#getHomeNode()}
 * stores a cluster node name (e.g. {@code maya-prosser}) — the brain
 * picks a fresh, dictionary-assembled node name on every boot so a
 * pod restart never inherits a stale ownership claim. Callers that
 * need an actual {@code host:port} resolve through
 * {@link ClusterService#resolveEndpoint(String)} against the live
 * {@code brain_pods} registry. See {@code specification/engine-message-routing.md}
 * §2 and {@code planning/project-home-cluster-refactor.md}.
 *
 * <p>Claim semantics are CAS: a claim succeeds when the home cluster
 * is currently {@code null}, equal to this pod's node name, or pointing
 * at a cluster node that the live-set says is gone. Two pods racing on
 * a fresh project deterministically pick one winner; the other gets
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
     * {@link ObjectProvider} because {@code ProjectLifecycleService} already
     * injects this manager — direct field-injection here would close a
     * constructor cycle Spring cannot resolve. The provider defers the
     * lookup to the first {@link #spawnNew} call, by which time both
     * beans are constructed.
     */
    private final ObjectProvider<ProjectLifecycleService> lifecycleServiceProvider;
    private final ClusterBringClient bringClient;
    /**
     * Optional — only present when the Cluster-Master role is enabled
     * cluster-wide. Used by {@link #spawnNew} to decide between local-first
     * bring and master-routed spawn.
     */
    private final ObjectProvider<ClusterMasterService> masterServiceProvider;

    /**
     * Ensures the project is owned by this pod. Refreshes
     * {@code homeNode} + {@code claimedAt} on the document; lifecycle
     * status is left untouched (transition runs via
     * {@code ProjectLifecycleService}). Throws {@link ClaimRejectedException}
     * when another live pod holds the claim, and on CLOSED or unknown.
     *
     * <p>Podless system projects (see {@link ProjectService#isPodless})
     * are returned unchanged — they live on whichever pod the user's
     * WS lands on and must not be pinned via {@code homeNode}.
     */
    public ProjectDocument claimForLocalPod(String tenantId, String projectName) {
        if (ProjectService.isPodless(projectName)) {
            return projectService.findByTenantAndName(tenantId, projectName)
                    .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                            "Project '" + projectName + "' not found in tenant '"
                                    + tenantId + "'"));
        }
        String selfCluster = clusterService.selfNodeName();
        Set<String> liveClusters = liveClustersIncludingSelf(selfCluster);
        ProjectDocument doc = projectService.claim(tenantId, projectName, selfCluster, liveClusters)
                .orElseThrow(() -> {
                    String holder = projectService.findByTenantAndName(tenantId, projectName)
                            .map(ProjectDocument::getHomeNode)
                            .orElse("<gone>");
                    return new ClaimRejectedException(
                            "Project '" + tenantId + "/" + projectName
                                    + "' is owned by live cluster '" + holder
                                    + "', refusing to steal from this pod ('"
                                    + selfCluster + "')");
                });
        log.debug("Project '{}/{}' claimed for cluster '{}'", tenantId, projectName, selfCluster);
        return doc;
    }

    /**
     * Asserts that {@code project} is owned by the local pod. Used by
     * call sites that want to surface a clear error before doing
     * project-scoped work without going through {@link #claimForLocalPod}.
     */
    public void requireOwnedByLocalPod(ProjectDocument project) {
        String selfCluster = clusterService.selfNodeName();
        if (!Objects.equals(selfCluster, project.getHomeNode())) {
            throw new ProjectNotOwnedException(
                    "Project '" + project.getName()
                            + "' is owned by cluster '" + project.getHomeNode()
                            + "', not this pod ('" + selfCluster + "')");
        }
    }

    /**
     * Place a newly-created project on a pod — local-first if this pod
     * has room, otherwise route through the Cluster-Master. Used by
     * {@link ProjectLifecycleService#create} and by the
     * {@code ProjectLocator} autoStart path. See
     * {@code specification/cluster-project-management.md} §5.3.
     *
     * <p>HOMELESS projects bypass placement entirely and are brought
     * pod-locally (the existing podless code path in {@code bring}).
     *
     * <p>Fallbacks when the cluster is in a degraded state:
     * <ul>
     *   <li>Master disabled cluster-wide → local bring with capacity overrun warning</li>
     *   <li>Master enabled but no live lease → local bring with warning (the new
     *       master will rebalance on its next distributor tick)</li>
     *   <li>Master responds {@code 503 cluster full} → rethrown to the caller</li>
     * </ul>
     */
    public void spawnNew(String tenantId, String projectName) {
        ProjectDocument project = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' not found in tenant '" + tenantId + "'"));

        if (project.getLifecycleType() == LifecycleType.HOMELESS) {
            lifecycleServiceProvider.getObject().bring(tenantId, projectName);
            return;
        }

        if (haveLocalRoom(project)) {
            lifecycleServiceProvider.getObject().bring(tenantId, projectName);
            return;
        }

        ClusterMasterService masterService = masterServiceProvider.getIfAvailable();
        if (masterService == null) {
            log.warn("ProjectManagerService: master disabled, bringing '{}/{}' locally despite capacity",
                    tenantId, projectName);
            lifecycleServiceProvider.getObject().bring(tenantId, projectName);
            return;
        }
        if (masterService.isLocalPodMaster()) {
            // I'm master and out of local room — let the placement service
            // pick another pod (it can dispatch remote bring on my behalf).
            // No-master fallback below; if nobody has room, ClusterFullException.
            placeViaMaster(project);
            return;
        }
        Optional<String> masterEndpoint = masterService.resolveMasterEndpoint();
        if (masterEndpoint.isEmpty()) {
            log.warn("ProjectManagerService: no master endpoint, bringing '{}/{}' locally as fallback",
                    tenantId, projectName);
            lifecycleServiceProvider.getObject().bring(tenantId, projectName);
            return;
        }
        bringClient.requestSpawn(masterEndpoint.get(), tenantId, projectName);
    }

    private void placeViaMaster(ProjectDocument project) {
        // We are the master, so we own the placement decision. Delegate to
        // ClusterPlacementService by going through the master's REST surface —
        // that keeps the placement logic in one place (single source of truth
        // for pick-pod heuristics). The REST hop to localhost is cheap.
        ClusterMasterService masterService = masterServiceProvider.getObject();
        Optional<String> selfEndpoint = masterService.resolveMasterEndpoint();
        if (selfEndpoint.isEmpty()) {
            log.warn("ProjectManagerService: master self-endpoint missing, bringing '{}/{}' locally",
                    project.getTenantId(), project.getName());
            lifecycleServiceProvider.getObject().bring(project.getTenantId(), project.getName());
            return;
        }
        bringClient.requestSpawn(selfEndpoint.get(), project.getTenantId(), project.getName());
    }

    private boolean haveLocalRoom(ProjectDocument project) {
        return clusterService.selfPod()
                .map(pod -> pod.getResourcesCurrentScore() + project.getHomeResourceScore()
                        <= pod.getResourcesMaxScore())
                .orElse(true);
    }

    /** All RUNNING projects this pod currently owns — for startup reclaim. */
    public List<ProjectDocument> projectsOwnedByLocalPod() {
        return projectService.findRunningByHomeNode(clusterService.selfNodeName());
    }

    /**
     * Returns the Brain-Endpoint (in {@code host:port} format) of the
     * Home Pod claiming the given project — that is, the brain process
     * where its sessions, processes, and workspace live.
     *
     * <p>Returns {@link Optional#empty()} if the project does not exist,
     * is podless ({@link ProjectService#isPodless}), has not yet been
     * claimed, or its {@code homeNode} points at a node that the
     * cluster registry no longer knows <em>or that has gone stale</em>
     * (crashed / lost its heartbeat — e.g. after a restart on a new host
     * IP). Callers that need a present endpoint should treat the empty
     * case as "lives wherever the WS lands" or "pending bootstrap" and
     * either retry or surface a {@code 409 Conflict} to the user.
     *
     * <p>The staleness filter is what stops a session from becoming
     * permanently unreachable after its home pod dies: without it,
     * {@link ClusterService#resolveEndpoint} happily returns the dead
     * pod's {@code host:port} (it only checks the row exists, not its
     * heartbeat), so every {@code session-resume} is tunnelled to a host
     * that no longer answers (observed 2026-07-01: connect timeout to a
     * pre-IP-change endpoint). Falling back to empty lets the live pod
     * that received the WS serve the session locally.
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
                .map(ProjectDocument::getHomeNode)
                .filter(c -> c != null && !c.isBlank())
                .flatMap(clusterService::resolveLiveEndpoint);
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
     *   <li>Project has no live home cluster (fresh, never claimed, or
     *       previous owner's node is gone): the CAS in
     *       {@link ProjectService#claim} grants the claim to this pod
     *       and we return {@link ClaimResult.Local}.</li>
     *   <li>Project's home cluster is this pod: the CAS refreshes the
     *       claim and we return {@link ClaimResult.Local}.</li>
     *   <li>Project's home cluster is another live pod: the CAS rejects
     *       (because the predicate {@code homeNode ∉ liveClusters} is
     *       false). We resolve the owning node's endpoint and return
     *       {@link ClaimResult.Redirect} so the caller can tunnel or
     *       reject.</li>
     * </ul>
     *
     * <p>Replaces the previous read-modify-write pattern with a single
     * atomic CAS. Two pods racing on a fresh project pick one winner,
     * never both.
     */
    public ClaimResult claimForLocalPodOrRedirect(String tenantId, String projectName) {
        if (ProjectService.isPodless(projectName)) {
            // Podless system projects (e.g. _user_<login>, _vance) live
            // wherever the WS lands — never redirect, never pin homeNode.
            return new ClaimResult.Local(claimForLocalPod(tenantId, projectName));
        }
        String selfCluster = clusterService.selfNodeName();
        Set<String> liveClusters = liveClustersIncludingSelf(selfCluster);
        Optional<ProjectDocument> claimed =
                projectService.claim(tenantId, projectName, selfCluster, liveClusters);
        if (claimed.isPresent()) {
            return new ClaimResult.Local(claimed.get());
        }
        // CAS rejected — re-read to find the current live holder and resolve to an endpoint.
        ProjectDocument current = projectService.findByTenantAndName(tenantId, projectName)
                .orElseThrow(() -> new ProjectService.ProjectNotFoundException(
                        "Project '" + projectName + "' vanished between claim and redirect"));
        String holder = current.getHomeNode();
        if (holder == null || holder.isBlank()) {
            // Shouldn't happen — null was an accepting CAS branch. Be defensive.
            throw new ClaimRejectedException(
                    "Project '" + tenantId + "/" + projectName
                            + "' claim rejected but home cluster is empty; concurrent state change");
        }
        String endpoint = clusterService.resolveLiveEndpoint(holder)
                .orElseThrow(() -> new ClaimRejectedException(
                        "Project '" + tenantId + "/" + projectName
                                + "' is owned by cluster '" + holder
                                + "' but the cluster registry has no live endpoint for it"));
        return new ClaimResult.Redirect(endpoint);
    }

    /**
     * Builds the live-cluster snapshot consumed by the claim CAS. Always
     * includes {@code selfCluster} — a fresh pod must not race itself out
     * of its own claim, even if its {@code brain_pods} row hasn't been
     * registered yet (single-pod boot, registration retry, etc.).
     */
    private Set<String> liveClustersIncludingSelf(String selfCluster) {
        Set<String> live = clusterService.liveClusterNodeNames();
        if (selfCluster == null || selfCluster.isBlank() || live.contains(selfCluster)) {
            return live;
        }
        // Copy-on-write — clusterService returns an unmodifiable set.
        java.util.HashSet<String> augmented = new java.util.HashSet<>(live);
        augmented.add(selfCluster);
        return java.util.Collections.unmodifiableSet(augmented);
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

    /**
     * Thrown by {@link #claimForLocalPod} when the CAS rejects the claim
     * because another live pod holds it. Distinct from
     * {@link ProjectNotOwnedException} (which is for assertion call sites
     * that already think they own the project).
     */
    public static class ClaimRejectedException extends RuntimeException {
        public ClaimRejectedException(String message) {
            super(message);
        }
    }
}
