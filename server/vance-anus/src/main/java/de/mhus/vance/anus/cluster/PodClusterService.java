package de.mhus.vance.anus.cluster;

import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.BrainCallException;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.ClusterMasterStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pod operations the admin shell performs: read the registry, resolve a pod by
 * either of its two names, configure placement, and check liveness.
 *
 * <p>Counterpart to {@link ProjectClusterService}, same shape and same reason —
 * these were only reachable by typing, and every one of them has outcomes a
 * caller must tell apart. Reads go straight to Mongo like the rest of the admin
 * shell; writes go over REST to {@code /internal/**}, because the brain owns
 * the write and {@code PodSelector.validate} sits on that path. A label written
 * past it is a label no selector can ever name.
 *
 * <p><b>The liveness result is an enum, and that is the point of moving it.</b>
 * It used to be a display string, and the prune path decided what to delete by
 * comparing against {@code "STALE"} — a rename of a table cell would have
 * quietly stopped pruning mismatched pods. The same information now has one
 * form for deciding ({@link PingResult}) and one for showing, which the command
 * builds.
 */
@Service
public class PodClusterService {

    private final BrainPodService brainPodService;
    private final ClusterMasterStore clusterMasterStore;
    private final AnusBrainClient brainClient;

    /**
     * Own instance — anus runs without web auto-configuration, so there is no
     * Jackson 3 mapper bean to inject.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public PodClusterService(
            BrainPodService brainPodService,
            ClusterMasterStore clusterMasterStore,
            AnusBrainClient brainClient) {
        this.brainPodService = brainPodService;
        this.clusterMasterStore = clusterMasterStore;
        this.brainClient = brainClient;
    }

    // ─── the registry ───────────────────────────────────────────────────────

    /**
     * Registered pods, optionally narrowed to one cluster, in a stable order.
     *
     * <p>Sorted here rather than at the call site because the order is a
     * decision, not a formatting choice: two runs of the same command have to
     * list the pods the same way, or a diff between them is noise.
     */
    public List<BrainPodDocument> pods(@Nullable String cluster) {
        List<BrainPodDocument> pods = StringUtils.isNotBlank(cluster)
                ? brainPodService.listCluster(cluster)
                : brainPodService.listAll();
        return pods.stream()
                .sorted(Comparator.comparing(BrainPodDocument::getClusterId)
                        .thenComparing(BrainPodDocument::getNodeName))
                .toList();
    }

    /**
     * Accepts a podId or a nodeName.
     *
     * <p>A nodeName that is ambiguous across clusters is rejected rather than
     * resolved to the first hit — picking one silently would write to a pod the
     * caller did not name.
     *
     * @throws IllegalArgumentException when nothing matches, or when a nodeName
     *     matches in more than one cluster. Thrown rather than returned because
     *     there is no useful half-answer: every caller either has a pod or has
     *     a mistyped argument.
     */
    public BrainPodDocument resolve(String podOrNode) {
        return brainPodService.findByPodId(podOrNode).orElseGet(() -> {
            List<BrainPodDocument> byName = pods(null).stream()
                    .filter(p -> podOrNode.equals(p.getNodeName()))
                    .toList();
            if (byName.isEmpty()) {
                throw new IllegalArgumentException("No pod with podId or nodeName '"
                        + podOrNode + "'");
            }
            if (byName.size() > 1) {
                throw new IllegalArgumentException("nodeName '" + podOrNode
                        + "' exists in " + byName.size() + " clusters — use the podId");
            }
            return byName.get(0);
        });
    }

    /**
     * The live cluster-master {@code podId} per cluster id, for the clusters the
     * given pods belong to.
     *
     * <p>A lease whose {@code leaseUntil} has elapsed is ignored — staleness is
     * observer-derived, the same way the brain dashboard treats it. Clusters
     * without a live master are cached as an empty string so the same cluster is
     * not queried twice.
     */
    public Map<String, String> liveMasterPodIds(List<BrainPodDocument> pods) {
        Instant now = Instant.now();
        Map<String, String> out = new HashMap<>();
        for (BrainPodDocument pod : pods) {
            String clusterId = pod.getClusterId();
            if (StringUtils.isBlank(clusterId) || out.containsKey(clusterId)) {
                continue;
            }
            clusterMasterStore.find(clusterId).ifPresent(lease -> {
                String podId = lease.getCurrentPodId();
                Instant leaseUntil = lease.getLeaseUntil();
                if (podId != null && !podId.isBlank()
                        && leaseUntil != null && leaseUntil.isAfter(now)) {
                    out.put(clusterId, podId);
                }
            });
            out.putIfAbsent(clusterId, "");
        }
        return out;
    }

    // ─── placement configuration ────────────────────────────────────────────

    public record PodPatch(boolean success, int statusCode, String detail) {}

    /**
     * One PATCH for every pod-placement change, so the wire shape exists once.
     *
     * <p>Only the fields the caller addressed go in. A {@code null} would read
     * as "leave alone" on the far end anyway, but omitting them keeps the wire
     * honest — and the body is serialised rather than concatenated because
     * label values are free-form input: a quote or a backslash in one would
     * otherwise travel as malformed JSON and come back as an unexplained 400.
     *
     * @param labels the whole new label map, or {@code null} to leave it alone.
     *     An empty map clears every label.
     * @param clearOverride drop the runtime capacity override — not the same
     *     statement as a {@code null} {@code maxScoreOverride}, which means
     *     "I am not talking about the override"
     */
    public PodPatch patch(
            String podId,
            @Nullable Map<String, String> labels,
            @Nullable Boolean exclusive,
            @Nullable Integer maxScoreOverride,
            boolean clearOverride) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (labels != null) {
            payload.put("labels", labels);
        }
        if (exclusive != null) {
            payload.put("exclusive", exclusive);
        }
        if (maxScoreOverride != null) {
            payload.put("maxScoreOverride", maxScoreOverride);
        }
        if (clearOverride) {
            payload.put("clearMaxScoreOverride", Boolean.TRUE);
        }
        Response response = brainClient.internal(
                "/internal/cluster/pods/" + podId + "/placement", "PATCH",
                objectMapper.writeValueAsString(payload));
        return new PodPatch(response.isSuccess(), response.statusCode(), response.body());
    }

    /** A pod's labels as a map, never {@code null} — the one place that decides. */
    public static Map<String, String> labelsOf(BrainPodDocument pod) {
        Map<String, String> labels = pod.getLabels();
        return labels == null ? Map.of() : labels;
    }

    /**
     * The labels with {@code pairs} merged in, keeping the rest.
     *
     * <p>Read-modify-write against an endpoint that replaces the whole map, so a
     * concurrent write from another actor between the read and the send is lost.
     * Acceptable for an interactive shell and stated rather than hidden; a
     * control loop reconciling a desired state passes the whole map instead.
     */
    public static Map<String, String> labelsWith(
            BrainPodDocument pod, Map<String, String> pairs) {
        Map<String, String> merged = new TreeMap<>(labelsOf(pod));
        merged.putAll(pairs);
        return merged;
    }

    /**
     * The labels with {@code keys} taken out, plus the keys that were not there.
     *
     * <p>The second half is why this is a method: removing an absent label
     * reaches the desired state, so it must not fail — but it should be
     * <em>said</em>, or a typo looks like a successful removal.
     */
    public static LabelRemoval labelsWithout(BrainPodDocument pod, List<String> keys) {
        Map<String, String> remaining = new TreeMap<>(labelsOf(pod));
        List<String> notFound = new ArrayList<>();
        for (String key : keys) {
            if (remaining.remove(key) == null) {
                notFound.add(key);
            }
        }
        return new LabelRemoval(remaining, List.copyOf(notFound));
    }

    public record LabelRemoval(Map<String, String> labels, List<String> keysNotFound) {}

    // ─── unmet demand ───────────────────────────────────────────────────────

    public record DemandReport(boolean success, int statusCode, String body) {}

    /** What the cluster could not place, optionally narrowed to one tenant. */
    public DemandReport demand(@Nullable String tenant) {
        String path = "/internal/cluster/placement/demand"
                + (StringUtils.isBlank(tenant) ? "" : "?tenant=" + tenant);
        Response response = brainClient.internal(path, "GET", null);
        return new DemandReport(response.isSuccess(), response.statusCode(), response.body());
    }

    // ─── liveness ───────────────────────────────────────────────────────────

    /**
     * What an end-to-end ping established.
     *
     * <p>{@link #STALE} is the one worth having: an HTTP 200 only proves that
     * <em>something</em> is on this address. Comparing the responding podId with
     * the registry row catches the common case of a fresh boot reusing the
     * {@code host:port} of an old, never-cleaned row.
     */
    public enum PingResult {
        /** Answered, and it is the pod the row says it is. */
        OK,
        /** Answered, but a different pod did. */
        STALE,
        /** Nothing answered. */
        UNREACHABLE,
        /** Answered with an error status. */
        HTTP_ERROR,
        /** Not attempted — the row advertises no endpoint. */
        SKIPPED
    }

    /**
     * @param statusCode the HTTP status, or {@code 0} when there was no response
     * @param detail short human-readable context; the responding node for
     *     {@link PingResult#OK} and {@link PingResult#STALE}, the error
     *     otherwise
     */
    public record PodPing(
            BrainPodDocument pod,
            PingResult result,
            int statusCode,
            @Nullable Duration latency,
            String detail,
            @Nullable String respondingNodeName) {}

    /** One ping per pod, in the order given. */
    public List<PodPing> ping(List<BrainPodDocument> pods, String tenant) {
        List<PodPing> rows = new ArrayList<>(pods.size());
        for (BrainPodDocument pod : pods) {
            rows.add(pingOne(pod, tenant));
        }
        return rows;
    }

    /** Mints a token for {@code tenant} and calls the pod's own endpoint. */
    public PodPing pingOne(BrainPodDocument pod, String tenant) {
        String endpoint = pod.getEndpoint();
        if (StringUtils.isBlank(endpoint)) {
            return new PodPing(pod, PingResult.SKIPPED, 0, null, "no endpoint advertised", null);
        }
        String baseUrl = endpoint.startsWith("http://") || endpoint.startsWith("https://")
                ? endpoint : "http://" + endpoint;
        Instant start = Instant.now();
        try {
            Response response = brainClient.getAt(
                    baseUrl, tenant, "/brain/" + tenant + "/admin/ping");
            Duration latency = Duration.between(start, Instant.now());
            if (!response.isSuccess()) {
                return new PodPing(pod, PingResult.HTTP_ERROR, response.statusCode(), latency,
                        response.body(), null);
            }
            String respondingPodId = extractValue(response.body(), "podId");
            String respondingNodeName = extractValue(response.body(), "nodeName");
            if (!pod.getPodId().equals(respondingPodId)) {
                return new PodPing(pod, PingResult.STALE, response.statusCode(), latency,
                        respondingPodId, respondingNodeName);
            }
            return new PodPing(pod, PingResult.OK, response.statusCode(), latency,
                    respondingNodeName, respondingNodeName);
        } catch (BrainCallException e) {
            return new PodPing(pod, PingResult.UNREACHABLE, 0,
                    Duration.between(start, Instant.now()),
                    String.valueOf(e.getMessage()), null);
        }
    }

    // ─── prune ──────────────────────────────────────────────────────────────

    /** Why a registry row should go. */
    public enum PruneReason {
        /** Never ticked, and past the grace window anchored on {@code bootedAt}. */
        NO_HEARTBEAT,
        /** Ticked, but too long ago. */
        STALE_HEARTBEAT,
        /** A different pod answers on this address. */
        LIVE_MISMATCH,
        /** Nothing answers on this address. */
        UNREACHABLE
    }

    /**
     * @param detail the timestamp, for the two heartbeat reasons
     * @param ping the probe behind {@link PruneReason#LIVE_MISMATCH} and
     *     {@link PruneReason#UNREACHABLE}, {@code null} for the others. Carried
     *     whole rather than pre-formatted so the caller renders a mismatch the
     *     same way it renders a ping — it is the same fact, and two formatters
     *     for it would drift.
     */
    public record PruneCandidate(
            BrainPodDocument pod,
            PruneReason reason,
            String detail,
            @Nullable PodPing ping) {}

    /**
     * Which rows are prunable, and why.
     *
     * <p>Staleness is checked first because it is free; the optional live probe
     * runs only for pods that pass that check, so an offline prune makes no HTTP
     * calls at all.
     *
     * <p>Finding candidates and deleting them are separate on purpose — the
     * dry-run that makes this command safe to run is the caller looking at the
     * list before asking for {@link #prune(List)}.
     */
    public List<PruneCandidate> pruneCandidates(
            List<BrainPodDocument> pods, Duration staleAfter, boolean probe, String tenant) {
        Instant now = Instant.now();
        List<PruneCandidate> candidates = new ArrayList<>();
        for (BrainPodDocument pod : pods) {
            PruneCandidate candidate = pruneReason(pod, now, staleAfter, probe, tenant);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private @Nullable PruneCandidate pruneReason(
            BrainPodDocument pod, Instant now, Duration staleAfter, boolean probe, String tenant) {
        Instant beat = pod.getLastHeartbeatAt();
        if (beat == null) {
            // No heartbeat ever recorded. Could mean "fresh registration, hasn't
            // ticked yet" — staleAfter gives a grace window anchored on
            // bootedAt instead.
            Instant booted = pod.getBootedAt();
            if (booted != null && booted.isBefore(now.minus(staleAfter))) {
                return new PruneCandidate(
                        pod, PruneReason.NO_HEARTBEAT, String.valueOf(booted), null);
            }
            return null;
        }
        if (beat.isBefore(now.minus(staleAfter))) {
            return new PruneCandidate(
                    pod, PruneReason.STALE_HEARTBEAT, String.valueOf(beat), null);
        }
        if (probe) {
            PodPing ping = pingOne(pod, tenant);
            if (ping.result() == PingResult.STALE) {
                return new PruneCandidate(pod, PruneReason.LIVE_MISMATCH, ping.detail(), ping);
            }
            if (ping.result() == PingResult.UNREACHABLE) {
                return new PruneCandidate(pod, PruneReason.UNREACHABLE, ping.detail(), ping);
            }
        }
        return null;
    }

    /** Deletes the rows and returns how many went. */
    public long prune(List<PruneCandidate> candidates) {
        long deleted = 0;
        for (PruneCandidate candidate : candidates) {
            deleted += brainPodService.deleteByPodId(candidate.pod().getPodId());
        }
        return deleted;
    }

    /**
     * A string value out of a JSON body by key.
     *
     * <p>Moved here unchanged, including its weakness: it is a substring search,
     * not a parse, and answers {@code "?"} for anything it cannot find. Good
     * enough for the two identity fields of a ping response, and replacing it
     * would change behaviour on malformed input — a separate decision from
     * moving the code.
     */
    static String extractValue(String body, String key) {
        int idx = body.indexOf("\"" + key + "\"");
        if (idx < 0) {
            return "?";
        }
        int colon = body.indexOf(':', idx);
        if (colon < 0) {
            return "?";
        }
        int firstQuote = body.indexOf('"', colon);
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return "?";
        }
        return body.substring(firstQuote + 1, secondQuote);
    }
}
