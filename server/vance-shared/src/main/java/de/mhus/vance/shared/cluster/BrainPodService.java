package de.mhus.vance.shared.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
     * For the writes that must not be read-modify-write: the heartbeat and the
     * placement update touch the same row from different directions.
     */
    private final MongoTemplate mongoTemplate;

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
     * Refresh the heartbeat timestamp + status + active-projects list +
     * resource scores of an existing pod row. Returns the updated
     * document; throws when the row has been removed externally (e.g.
     * by an admin purge).
     *
     * <p>The three score fields are written on every beat. Only one of them
     * <em>needs</em> to be: {@code resourcesCurrentScore} is derived per beat
     * and is the pod's own measurement of its load. The other two re-assert the
     * pod's configuration, and the original justification for that — "so a
     * configuration change reaches the registry without a separate update path"
     * — does not hold: {@code ClusterProperties} is bound once at context
     * startup (no {@code @RefreshScope} anywhere), so a changed value needs a
     * restart, and a restart registers a <em>new</em> row that carries it
     * anyway. They are kept because re-asserting them costs nothing in the same
     * update and makes a hand-edited row self-healing.
     *
     * <p><b>{@code resourcesMaxScoreOverride} is deliberately not in this
     * list.</b> That is the runtime correction, and re-asserting it here would
     * delete it within a minute — the same trap the labels below are exempt
     * from. See {@link BrainPodCapacity}.
     *
     * <p>The {@code endpoint} is re-written on every beat too: a running
     * process whose host address changed underneath it (laptop sleep/resume,
     * DHCP lease change) must re-advertise its current {@code host:port} so
     * peers — and its own workspace self-proxy — stop dialling a dead address.
     *
     * <p><b>A targeted update, not a read-modify-write.</b> It used to load the
     * row, set seven fields and save the whole document back — which silently
     * made the beat authoritative over <em>every</em> field, including ones the
     * pod does not own. {@link BrainPodDocument#getLabels()} is written from
     * outside the process, so a concurrent label write landing between that
     * read and that save was lost, and the loss window was the whole heartbeat
     * interval. Writing only the fields this beat actually knows about is both
     * the fix and one round trip cheaper on the registry's hottest path.
     */
    public BrainPodDocument heartbeat(
            String podId,
            Instant now,
            PodStatus status,
            String endpoint,
            List<String> activeProjects,
            int currentScore,
            int startupScore,
            int maxScore) {
        Update update = new Update()
                .set("lastHeartbeatAt", now)
                .set("status", status)
                .set("endpoint", endpoint)
                .set("activeProjects", List.copyOf(activeProjects))
                .set("resourcesCurrentScore", currentScore)
                .set("resourcesStartupScore", startupScore)
                .set("resourcesMaxScore", maxScore);
        BrainPodDocument updated = mongoTemplate.findAndModify(
                new Query(Criteria.where("podId").is(podId)), update,
                FindAndModifyOptions.options().returnNew(true),
                BrainPodDocument.class);
        if (updated == null) {
            throw new IllegalStateException(
                    "Brain pod row missing for podId='" + podId + "' — was it purged?");
        }
        return updated;
    }

    /**
     * Replaces this pod's placement attributes. Both arguments are optional:
     * {@code null} means "leave unchanged", so a caller can set labels without
     * taking a position on {@code exclusive} and the other way round.
     *
     * <p>{@code labels} is replaced wholesale rather than merged. A control
     * loop that reconciles a desired state needs to be able to <em>remove</em>
     * a label, and with merge semantics the only way to do that would be a
     * second verb.
     *
     * <p>{@code resourcesMaxScoreOverride} is the runtime correction of the
     * configured cap and needs an extra parameter to clear it: {@code null}
     * already means "leave alone" for every field here, so "back to the
     * configured value" would otherwise be indistinguishable from saying
     * nothing. See {@link BrainPodCapacity} for why it is a second field rather
     * than an overwrite of the configured one.
     *
     * @throws PodSelector.InvalidLabelException on a key or value outside the
     *     grammar — checked here so nothing unmatchable reaches persistence
     * @throws IllegalArgumentException on an override below 1
     * @return the updated row, or empty when no pod carries {@code podId}
     */
    public Optional<BrainPodDocument> updatePlacement(
            String podId,
            @Nullable Map<String, String> labels,
            @Nullable Boolean exclusive,
            @Nullable Integer maxScoreOverride,
            boolean clearMaxScoreOverride) {
        PodSelector.validate(labels);
        if (maxScoreOverride != null && maxScoreOverride < 1) {
            throw new IllegalArgumentException(
                    "resourcesMaxScoreOverride must be at least 1 (was " + maxScoreOverride
                            + ") — a cap of zero would read as a permanently full pod");
        }
        Update update = new Update();
        if (labels != null) {
            update.set("labels", Map.copyOf(labels));
        }
        if (exclusive != null) {
            update.set("exclusive", exclusive);
        }
        // Clearing needs its own flag, because null already means "leave alone"
        // for every other field here and the caller must be able to say "back to
        // the configured value" without that being indistinguishable from
        // saying nothing.
        if (clearMaxScoreOverride) {
            update.unset("resourcesMaxScoreOverride");
        } else if (maxScoreOverride != null) {
            update.set("resourcesMaxScoreOverride", maxScoreOverride);
        }
        if (update.getUpdateObject().isEmpty()) {
            return findByPodId(podId);
        }
        return Optional.ofNullable(mongoTemplate.findAndModify(
                new Query(Criteria.where("podId").is(podId)), update,
                FindAndModifyOptions.options().returnNew(true),
                BrainPodDocument.class));
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
     * startup-cleanup sweep to decide which {@code ProjectDocument.homeNode}
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
     * Routing-grade resolve: like {@link #resolveEndpoint} but returns an
     * endpoint <em>only</em> when the target node is backed by a live pod
     * (not {@link PodStatus#STOPPED} and heartbeat within {@code staleAfter}).
     *
     * <p>This is the primitive every cross-pod router must use. Plain
     * {@link #resolveEndpoint} only checks that the {@code brain_pods} row
     * exists — a crashed pod's row lingers at {@code RUNNING} and a cleanly
     * stopped one at {@code STOPPED}, both keeping their old {@code endpoint}.
     * Routing to those produces connect timeouts (observed repeatedly for the
     * workspace file proxy). Filtering staleness here lets callers treat an
     * empty result as "no live owner — adopt/serve locally or surface 409".
     *
     * <p>A raw {@code host:port} home (colon form — legacy / external) can't
     * be liveness-checked by node-name, so it is trusted as-is; only the
     * node-name form is gated against status + heartbeat.
     */
    public Optional<String> resolveLiveEndpoint(String clusterId, String nodeNameOrEndpoint,
                                                Duration staleAfter) {
        if (nodeNameOrEndpoint == null || nodeNameOrEndpoint.isBlank()) return Optional.empty();
        if (nodeNameOrEndpoint.contains(":")) {
            return Optional.of(nodeNameOrEndpoint);
        }
        Instant now = Instant.now();
        return findByNodeName(clusterId, nodeNameOrEndpoint)
                .filter(doc -> doc.getStatus() != PodStatus.STOPPED)
                .filter(doc -> !isStale(doc, now, staleAfter))
                .map(BrainPodDocument::getEndpoint);
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
