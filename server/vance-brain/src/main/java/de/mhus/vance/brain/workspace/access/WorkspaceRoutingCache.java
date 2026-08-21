package de.mhus.vance.brain.workspace.access;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectOwnership;
import de.mhus.vance.shared.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-memory cache that maps {@code (tenant, project)} to the lease holder's
 * endpoint ({@code host:port}). Populated lazily: the project document names
 * the holding pod, this cache resolves it once through
 * {@link ClusterService#resolveEndpointByPodId(String)} and remembers the
 * result until the entry expires or is invalidated. A project whose lease
 * expired resolves to empty and is forgotten on the next miss, so the caller
 * adopts it locally. See {@code specification/workspace-access.md} §4.
 *
 * <p>The cache is now genuinely a <em>cache</em>: it saves the endpoint
 * lookup, not a liveness verdict. Before the lease it also cached the answer
 * to "is that node still alive", which is exactly the kind of thing a TTL
 * cache must not hold.
 */
@Component
@Slf4j
public class WorkspaceRoutingCache {

    record PodEntry(String endpoint, Instant lastUsed) {
    }

    private final ConcurrentMap<ProjectPodKey, PodEntry> entries = new ConcurrentHashMap<>();
    private final ProjectService projectService;
    private final ClusterService clusterService;
    private final Duration ttl;

    public WorkspaceRoutingCache(ProjectService projectService,
                                 ClusterService clusterService,
                                 WorkspaceAccessProperties properties) {
        this.projectService = projectService;
        this.clusterService = clusterService;
        this.ttl = properties.getCacheTtl();
    }

    /**
     * Resolve the owner pod endpoint for a project. Returns empty when
     * the project does not exist, has not been claimed yet, or its
     * {@code homeNode} points at a cluster node the registry no
     * longer knows. Does not validate reachability — callers handle
     * that and call {@link #invalidate} on connect failure.
     */
    public Optional<String> lookup(ProjectPodKey key) {
        Instant now = Instant.now();
        PodEntry cached = entries.get(key);
        if (cached != null && !isExpired(cached, now)) {
            entries.put(key, new PodEntry(cached.endpoint(), now));
            return Optional.of(cached.endpoint());
        }
        Optional<String> fresh = readFromMongo(key);
        fresh.ifPresent(endpoint -> entries.put(key, new PodEntry(endpoint, now)));
        if (fresh.isEmpty()) {
            entries.remove(key);
        }
        return fresh;
    }

    /**
     * Force a fresh Mongo read, bypassing the cache. Used after a connect
     * failure to give the routing one more shot before giving up.
     */
    public Optional<String> refresh(ProjectPodKey key) {
        entries.remove(key);
        Optional<String> fresh = readFromMongo(key);
        fresh.ifPresent(endpoint -> entries.put(key, new PodEntry(endpoint, Instant.now())));
        return fresh;
    }

    /** Drop the cached entry — typically after a connect failure. */
    public void invalidate(ProjectPodKey key) {
        entries.remove(key);
    }

    /**
     * True when <em>this</em> pod holds the project's lease. The holder always
     * has the project's workspace on its own filesystem, so the caller must
     * serve it locally and never proxy to its own advertised endpoint: a pod
     * cannot reliably reach itself via that endpoint (a dev box after an IP
     * change; a k8s pod's own Pod-IP/ClusterIP depending on CNI hairpin
     * config).
     *
     * <p>Comparison is by pod <b>id</b>, not endpoint, so it is immune to an
     * advertised-IP change since boot — and, unlike the node name it used
     * before, immune to a restart under a pinned {@code vance.cluster.node-name}
     * recognising its dead predecessor's claim as its own. Orthogonal to
     * {@link #lookup}: a lease held by a <em>foreign</em> pod still resolves to
     * that pod's endpoint and is proxied; only self-ownership short-circuits.
     */
    public boolean isSelfOwned(ProjectPodKey key) {
        return projectService.findByTenantAndName(key.tenantId(), key.projectName())
                .map(project -> ProjectOwnership.isOwnedBy(
                        project, clusterService.selfPodId(),
                        Instant.now(), clusterService.leaseTtl()))
                .orElse(false);
    }

    private Optional<String> readFromMongo(ProjectPodKey key) {
        Optional<ProjectDocument> doc =
                projectService.findByTenantAndName(key.tenantId(), key.projectName());
        if (doc.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> holder = ProjectOwnership.liveOwnerPodId(
                doc.get(), Instant.now(), clusterService.leaseTtl());
        if (holder.isEmpty()) {
            log.debug("Project {}/{} holds no valid lease (never claimed, or the holder "
                            + "stopped renewing); caller adopts locally",
                    key.tenantId(), key.projectName());
            return Optional.empty();
        }
        Optional<String> endpoint = clusterService.resolveEndpointByPodId(holder.get());
        if (endpoint.isEmpty()) {
            log.debug("Project {}/{} is leased by pod '{}' but the cluster registry has no "
                            + "endpoint row for it; caller adopts locally",
                    key.tenantId(), key.projectName(), doc.get().getHomeNode());
        }
        return endpoint;
    }

    private boolean isExpired(PodEntry entry, Instant now) {
        if (ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return entry.lastUsed().plus(ttl).isBefore(now);
    }
}
