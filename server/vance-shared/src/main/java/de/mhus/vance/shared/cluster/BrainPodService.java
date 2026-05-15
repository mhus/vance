package de.mhus.vance.shared.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * CRUD + lookup for the brain-pod cluster registry. Pure persistence —
 * the business policy (when to register, when to heartbeat, which
 * projects are "mine") lives one floor up in {@code ClusterService}
 * (vance-brain).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrainPodService {

    private final BrainPodRepository repository;

    /**
     * Insert a new pod row. Throws on duplicate {@code (clusterId, nodeName)}
     * so the caller can re-roll the node name and retry.
     */
    public BrainPodDocument register(BrainPodDocument doc) {
        try {
            return repository.save(doc);
        } catch (DuplicateKeyException e) {
            throw new NodeNameTakenException(
                    "Cluster '" + doc.getClusterId() + "' already has a pod named '"
                            + doc.getNodeName() + "'", e);
        }
    }

    /**
     * Refresh the heartbeat timestamp + status + active-projects list of an
     * existing pod row. Returns the updated document; throws when the row
     * has been removed externally (e.g. by an admin purge).
     */
    public BrainPodDocument heartbeat(
            String podId,
            Instant now,
            PodStatus status,
            List<String> activeProjects) {
        BrainPodDocument doc = repository.findByPodId(podId)
                .orElseThrow(() -> new IllegalStateException(
                        "Brain pod row missing for podId='" + podId + "' — was it purged?"));
        doc.setLastHeartbeatAt(now);
        doc.setStatus(status);
        doc.setActiveProjects(List.copyOf(activeProjects));
        return repository.save(doc);
    }

    /** Set status + lastHeartbeatAt without touching active-projects. Used on shutdown. */
    public Optional<BrainPodDocument> setStatus(String podId, PodStatus status, Instant when) {
        return repository.findByPodId(podId).map(doc -> {
            doc.setStatus(status);
            doc.setLastHeartbeatAt(when);
            return repository.save(doc);
        });
    }

    public Optional<BrainPodDocument> findByPodId(String podId) {
        return repository.findByPodId(podId);
    }

    public Optional<BrainPodDocument> findByNodeName(String clusterId, String nodeName) {
        return repository.findByClusterIdAndNodeName(clusterId, nodeName);
    }

    public List<BrainPodDocument> listCluster(String clusterId) {
        return repository.findByClusterId(clusterId);
    }

    /**
     * Returns the {@code nodeName}s of every pod in {@code clusterId} that
     * is not {@link PodStatus#STOPPED} and whose last heartbeat is within
     * {@code staleAfter}. Used by the project-claim CAS predicate and the
     * startup-cleanup sweep to decide which {@code ProjectDocument.homeCluster}
     * values are still backed by a live pod.
     *
     * <p>Pods without a heartbeat yet (just registered, still in their
     * grace period) count as live — see {@link #isStale}. Stopped pods
     * are always excluded.
     */
    public Set<String> listLiveClusterNodeNames(String clusterId, Duration staleAfter) {
        Instant now = Instant.now();
        return repository.findByClusterId(clusterId).stream()
                .filter(doc -> doc.getStatus() != PodStatus.STOPPED)
                .filter(doc -> !isStale(doc, now, staleAfter))
                .map(BrainPodDocument::getNodeName)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Every registered pod, regardless of cluster. Used by admin tooling. */
    public List<BrainPodDocument> listAll() {
        return repository.findAll();
    }

    public boolean nodeNameTaken(String clusterId, String nodeName) {
        return repository.existsByClusterIdAndNodeName(clusterId, nodeName);
    }

    /** Hard-delete a row. Used by admin / tests; the normal lifecycle uses {@link #setStatus}. */
    public long deleteByPodId(String podId) {
        return repository.deleteByPodId(podId);
    }

    /**
     * Observer-side staleness predicate. {@code lastHeartbeatAt} is
     * considered stale once it's older than {@code now - staleAfter}.
     * Pods without any heartbeat (right after registration) count as
     * not-stale to avoid a transient flap during the boot grace period.
     */
    public boolean isStale(BrainPodDocument doc, Instant now, Duration staleAfter) {
        Instant beat = doc.getLastHeartbeatAt();
        if (beat == null) return false;
        return beat.isBefore(now.minus(staleAfter));
    }

    /**
     * Resolves a {@code nodeName} (or raw {@code endpoint} fall-through)
     * to the live endpoint string. Returns empty if the name is unknown
     * in the cluster — caller can then surface a clean error to the
     * admin instead of trying to dial a nonexistent host.
     */
    public Optional<String> resolveEndpoint(String clusterId, String nodeNameOrEndpoint) {
        if (nodeNameOrEndpoint == null || nodeNameOrEndpoint.isBlank()) return Optional.empty();
        // Heuristic: a colon means it's already an endpoint (host:port).
        // No colon → look it up as a node name.
        if (nodeNameOrEndpoint.contains(":")) {
            return Optional.of(nodeNameOrEndpoint);
        }
        return findByNodeName(clusterId, nodeNameOrEndpoint).map(BrainPodDocument::getEndpoint);
    }

    /**
     * Thrown when {@link #register(BrainPodDocument)} hits a name collision
     * on the {@code (clusterId, nodeName)} unique index. Callers (i.e.
     * {@code ClusterService}) should re-roll a fresh name and retry.
     */
    public static class NodeNameTakenException extends RuntimeException {
        public NodeNameTakenException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
