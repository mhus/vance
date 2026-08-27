package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.BrainCallException;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.shared.cluster.BrainPodCapacity;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.cluster.ClusterMasterStore;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Cluster-side admin: list pods registered in {@code brain_pods}, and
 * fire an authenticated end-to-end ping against each one. The ping is
 * the canonical proof that the JWT path works — token mint, bearer
 * header, server-side filter, controller, all in one round-trip.
 */
@Component
@RequiresAuth
@RequiredArgsConstructor
public class ClusterCommands {

    private final BrainPodService brainPodService;
    /**
     * Own instance — anus runs without Spring Boot's web auto-configuration, so
     * there is no auto-registered Jackson 3 mapper bean to inject. Same reason
     * and same shape as {@code ProjectKitsCommands}.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final ClusterMasterStore clusterMasterStore;
    private final AnusBrainClient brainClient;

    @Command(name = {"cluster", "list"},
            description = "List registered brain pods. Filter by --cluster (default: all clusters). "
                    + "The MASTER column carries '*' for the pod that currently holds the "
                    + "cluster-master lease — expired/absent leases leave the column blank.")
    public String list(
            @Option(longName = "cluster", shortName = 'c',
                    description = "Cluster id to filter by. Omit to list every pod regardless of cluster.")
            @Nullable String cluster) {
        List<BrainPodDocument> pods = loadPods(cluster);
        if (pods.isEmpty()) {
            return cluster == null ? "(no pods registered)"
                    : "(no pods registered in cluster '" + cluster + "')";
        }
        Map<String, String> liveMasterPodIdByCluster = liveMasterPodIdByCluster(pods);
        return Tables.render(
                List.of("CLUSTER", "NODE", "MASTER", "PODID", "ENDPOINT", "STATUS",
                        "LABELS", "EXCL", "SCORE", "VERSION", "LASTBEAT"),
                List.<Function<BrainPodDocument, @Nullable Object>>of(
                        BrainPodDocument::getClusterId,
                        BrainPodDocument::getNodeName,
                        p -> p.getPodId().equals(liveMasterPodIdByCluster.get(p.getClusterId()))
                                ? "*" : "",
                        BrainPodDocument::getPodId,
                        BrainPodDocument::getEndpoint,
                        BrainPodDocument::getStatus,
                        ClusterCommands::renderLabels,
                        p -> p.isExclusive() ? (renderLabels(p).isEmpty() ? "cordon" : "yes") : "",
                        ClusterCommands::renderScore,
                        BrainPodDocument::getVersion,
                        BrainPodDocument::getLastHeartbeatAt),
                pods);
    }

    /**
     * Reads the {@code cluster_master} lease for every distinct cluster
     * in {@code pods} and returns the live master {@code podId} per
     * cluster id. A lease whose {@code leaseUntil} has elapsed is
     * ignored — staleness is observer-derived, the same way the brain
     * dashboard treats it.
     */
    private Map<String, String> liveMasterPodIdByCluster(List<BrainPodDocument> pods) {
        Instant now = Instant.now();
        Map<String, String> out = new HashMap<>();
        for (BrainPodDocument pod : pods) {
            String clusterId = pod.getClusterId();
            if (StringUtils.isBlank(clusterId) || out.containsKey(clusterId)) continue;
            clusterMasterStore.find(clusterId).ifPresent(lease -> {
                String podId = lease.getCurrentPodId();
                Instant leaseUntil = lease.getLeaseUntil();
                if (podId != null && !podId.isBlank()
                        && leaseUntil != null && leaseUntil.isAfter(now)) {
                    out.put(clusterId, podId);
                }
            });
            // Cache "no live master" too so we don't re-query the same cluster.
            out.putIfAbsent(clusterId, "");
        }
        return out;
    }

    /**
     * Labels as a sorted {@code k=v,k=v} string. Sorted so two pods carrying the
     * same labels render identically — map iteration order is not a property of
     * the pod, and a table where the same thing looks different twice is worse
     * than one that is a little wider.
     */
    static String renderLabels(BrainPodDocument pod) {
        Map<String, String> labels = pod.getLabels();
        if (labels == null || labels.isEmpty()) return "";
        return new TreeMap<>(labels).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * {@code current/effective}, with a {@code *} when a runtime override is in
     * effect. The marker matters more than the number: an overridden cap
     * silently returns to the configured value on the next re-registration, so
     * the column has to distinguish "this pod is small" from "somebody made it
     * small" — see {@code BrainPodCapacity}.
     */
    static String renderScore(BrainPodDocument pod) {
        return pod.getResourcesCurrentScore() + "/" + BrainPodCapacity.effectiveMaxScore(pod)
                + (BrainPodCapacity.isOverridden(pod) ? "*" : "");
    }

    @Command(name = {"cluster", "placement", "demand"},
            description = "What the cluster could not place: which kind of pod is missing, "
                    + "how much score is waiting, and since when.")
    public String placementDemand(
            @Option(longName = "tenant", shortName = 'T',
                    description = "Narrow to one tenant. Omit for the whole cluster.")
            @Nullable String tenant) {
        String path = "/internal/cluster/placement/demand"
                + (StringUtils.isBlank(tenant) ? "" : "?tenant=" + tenant);
        Response response = brainClient.internal(path, "GET", null);
        if (!response.isSuccess()) {
            return "(failed: HTTP " + response.statusCode() + " " + response.body() + ")";
        }
        return response.body();
    }

    // ─── Pod placement ──────────────────────────────────────────────
    //
    // Reads go straight to Mongo like the rest of this class; writes go over
    // REST to /internal/**, because the brain owns the write. Two reasons, and
    // the second is the load-bearing one: PodSelector.validate lives on that
    // path, and a label written past it is a label no selector can ever name.
    // Same split ProjectCommands already uses for lifecycle-type.

    @Command(name = {"cluster", "pod", "show"},
            description = "Everything about one pod, including placement labels, the "
                    + "exclusive flag and both capacity layers.")
    public String podShow(
            @Option(longName = "pod", shortName = 'p', required = true,
                    description = "podId, or a nodeName that is unique across clusters.")
            String pod) {
        return withPod(pod, this::renderPod);
    }

    private String renderPod(BrainPodDocument doc) {
        Map<String, String> labels = doc.getLabels() == null
                ? Map.of() : new TreeMap<>(doc.getLabels());
        StringBuilder out = new StringBuilder();
        out.append("cluster       ").append(doc.getClusterId()).append('\n');
        out.append("node          ").append(doc.getNodeName()).append('\n');
        out.append("podId         ").append(doc.getPodId()).append('\n');
        out.append("endpoint      ").append(doc.getEndpoint()).append('\n');
        out.append("status        ").append(doc.getStatus()).append('\n');
        out.append("version       ").append(nullToDash(doc.getVersion())).append('\n');
        out.append("booted        ").append(nullToDash(doc.getBootedAt())).append('\n');
        out.append("lastBeat      ").append(nullToDash(doc.getLastHeartbeatAt())).append('\n');
        out.append("exclusive     ").append(doc.isExclusive());
        if (doc.isExclusive() && labels.isEmpty()) {
            out.append("   (cordoned — nothing matches this pod at all)");
        }
        out.append('\n');
        out.append("labels        ").append(labels.isEmpty() ? "(none — accepts any project)"
                : renderLabels(doc)).append('\n');
        out.append("score         ").append(doc.getResourcesCurrentScore())
                .append(" / ").append(BrainPodCapacity.effectiveMaxScore(doc))
                .append("  (headroom ").append(BrainPodCapacity.headroom(doc)).append(")\n");
        out.append("maxScore      ").append(doc.getResourcesMaxScore())
                .append("  (configured)\n");
        // Spelled out rather than folded into the line above: the override
        // disappears on the next re-registration, and a reader who cannot see
        // that it is an override has no way to expect that.
        if (BrainPodCapacity.isOverridden(doc)) {
            out.append("  override    ").append(doc.getResourcesMaxScoreOverride())
                    .append("  (set at runtime — reverts to configured on pod restart "
                            + "or row re-registration)\n");
        }
        out.append("startupScore  ").append(doc.getResourcesStartupScore())
                .append("  (informational — the self-pull reads its budget from config, "
                        + "not from this row)\n");
        out.append("projects      ").append(doc.getActiveProjects() == null
                || doc.getActiveProjects().isEmpty()
                ? "(none)" : String.join(", ", doc.getActiveProjects())).append('\n');
        return out.toString();
    }

    @Command(name = {"cluster", "pod", "label-set"},
            description = "Replace ALL labels of a pod. Pass k=v pairs, or none to clear. "
                    + "Use label-add / label-rm to change single entries.")
    public String podLabelSet(
            @Option(longName = "pod", shortName = 'p', required = true) String pod,
            @Option(longName = "labels", shortName = 'l',
                    description = "Comma-separated k=v pairs. Omit entirely to remove "
                            + "every label.")
            @Nullable String labels) {
        return withPod(pod, doc -> patchPod(doc, parsePairs(labels), null, null, false));
    }

    @Command(name = {"cluster", "pod", "label-add"},
            description = "Add or overwrite single labels, keeping the rest.")
    public String podLabelAdd(
            @Option(longName = "pod", shortName = 'p', required = true) String pod,
            @Option(longName = "labels", shortName = 'l', required = true,
                    description = "Comma-separated k=v pairs to set.")
            String labels) {
        return withPod(pod, doc -> {
            // Read-modify-write, and the endpoint replaces the whole map — so a
            // concurrent write from another actor between these two steps is
            // lost. Acceptable for an interactive shell and stated rather than
            // hidden; a control loop reconciling a desired state uses label-set.
            Map<String, String> merged = new TreeMap<>(
                    doc.getLabels() == null ? Map.of() : doc.getLabels());
            merged.putAll(parsePairs(labels));
            return patchPod(doc, merged, null, null, false);
        });
    }

    @Command(name = {"cluster", "pod", "label-rm"},
            description = "Remove single labels by key, keeping the rest.")
    public String podLabelRm(
            @Option(longName = "pod", shortName = 'p', required = true) String pod,
            @Option(longName = "keys", shortName = 'k', required = true,
                    description = "Comma-separated label keys to remove.")
            String keys) {
        return withPod(pod, doc -> {
            Map<String, String> remaining = new TreeMap<>(
                    doc.getLabels() == null ? Map.of() : doc.getLabels());
            List<String> missing = new ArrayList<>();
            for (String k : splitCsv(keys)) {
                if (remaining.remove(k) == null) missing.add(k);
            }
            String result = patchPod(doc, remaining, null, null, false);
            // Reported, not treated as an error: removing a label that is not
            // there reaches the desired state, and failing would make the
            // command non-idempotent for no gain.
            return missing.isEmpty() ? result
                    : result + "\n(no such label, nothing removed: "
                            + String.join(", ", missing) + ")";
        });
    }

    @Command(name = {"cluster", "pod", "exclusive"},
            description = "Set whether a project without a placement selector may run here. "
                    + "With no labels, exclusive=true is a full cordon.")
    public String podExclusive(
            @Option(longName = "pod", shortName = 'p', required = true) String pod,
            @Option(longName = "value", shortName = 'v', required = true,
                    description = "true | false")
            boolean value) {
        return withPod(pod, doc -> patchPod(doc, null, value, null, false));
    }

    @Command(name = {"cluster", "pod", "max-score"},
            description = "Override the pod's capacity cap at runtime, or --clear to fall back "
                    + "to the configured value. Does NOT survive a pod restart.")
    public String podMaxScore(
            @Option(longName = "pod", shortName = 'p', required = true) String pod,
            @Option(longName = "value", shortName = 'v',
                    description = "New effective cap, at least 1.")
            @Nullable String value,
            @Option(longName = "clear",
                    description = "Drop the override and use the configured maxScore again.",
                    defaultValue = "false")
            boolean clear) {
        boolean hasValue = !StringUtils.isBlank(value);
        if (clear == hasValue) {
            return "(specify either --value <n> or --clear, not both and not neither)";
        }
        Integer parsed = null;
        if (hasValue) {
            // Parsed here rather than bound: a non-numeric argument must come
            // back as a CLI message, not as a framework binding failure that
            // says nothing about which option was wrong.
            try {
                parsed = Integer.valueOf(value.trim());
            } catch (NumberFormatException e) {
                return "(--value must be an integer, got '" + value + "')";
            }
        }
        Integer effective = parsed;
        return withPod(pod, doc -> patchPod(doc, null, null, effective, clear));
    }

    /**
     * One PATCH for every pod-placement command, so the wire shape and the
     * "what did it become" echo exist once.
     */
    private String patchPod(
            BrainPodDocument doc,
            @Nullable Map<String, String> labels,
            @Nullable Boolean exclusive,
            @Nullable Integer maxScoreOverride,
            boolean clearOverride) {
        // Serialised, not concatenated: label values are free-form user input,
        // and a quote or a backslash in one would otherwise travel as malformed
        // JSON and come back as an unexplained 400. Only the fields the caller
        // actually addressed go in — a null in this map would read as "leave
        // alone" on the far end anyway, but omitting them keeps the wire honest.
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (labels != null) payload.put("labels", labels);
        if (exclusive != null) payload.put("exclusive", exclusive);
        if (maxScoreOverride != null) payload.put("maxScoreOverride", maxScoreOverride);
        if (clearOverride) payload.put("clearMaxScoreOverride", Boolean.TRUE);
        String body = objectMapper.writeValueAsString(payload);
        Response response = brainClient.internal(
                "/internal/cluster/pods/" + doc.getPodId() + "/placement", "PATCH", body);
        if (!response.isSuccess()) {
            return "(failed: HTTP " + response.statusCode() + " " + response.body() + ")";
        }
        return "pod '" + doc.getNodeName() + "' → " + response.body();
    }

    /**
     * Resolves the pod and runs {@code action}, turning every argument problem
     * into a returned message.
     *
     * <p>Necessary, not cosmetic: Spring Shell wraps a thrown exception in a
     * {@code CommandExecutionException}, and in {@code --sudo} that surfaces as
     * "Unable to execute command cluster pod show" with the actual reason
     * nowhere to be seen. Measured against a running brain. Every other command
     * in this shell returns its errors as text, so this follows suit.
     */
    private String withPod(String podOrNode, Function<BrainPodDocument, String> action) {
        try {
            return action.apply(resolvePod(podOrNode));
        } catch (IllegalArgumentException e) {
            // Covers the pod lookup and the k=v grammar inside the action —
            // both are the caller mistyping an argument.
            return "(" + e.getMessage() + ")";
        }
    }

    /**
     * Accepts a podId or a nodeName. A nodeName that is ambiguous across
     * clusters is rejected rather than resolved to the first hit — picking one
     * silently would write to a pod the caller did not name.
     */
    private BrainPodDocument resolvePod(String podOrNode) {
        return brainPodService.findByPodId(podOrNode).orElseGet(() -> {
            List<BrainPodDocument> byName = loadPods(null).stream()
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
     * Comma-separated {@code k=v} pairs to a map, rejecting anything without a
     * single interior {@code =}. Comma-separated rather than a repeated option
     * because this Spring Shell {@code @Option} has no arity — same convention
     * {@code project create --teams} already uses.
     */
    private static Map<String, String> parsePairs(@Nullable String csv) {
        Map<String, String> out = new TreeMap<>();
        for (String pair : splitCsv(csv)) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                throw new IllegalArgumentException(
                        "Expected k=v, got '" + pair + "'");
            }
            out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    private static List<String> splitCsv(@Nullable String csv) {
        if (StringUtils.isBlank(csv)) return List.of();
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String nullToDash(@Nullable Object value) {
        return value == null ? "—" : value.toString();
    }

    @Command(name = {"cluster", "prune"},
            description = "List (or remove with --apply) brain_pods rows that are stale or "
                    + "respond as a different podId. Default is dry-run.")
    public String prune(
            @Option(longName = "cluster", shortName = 'c',
                    description = "Cluster id to filter by. Omit to scan every cluster.")
            @Nullable String cluster,
            @Option(longName = "stale-after",
                    description = "Heartbeat threshold. Pods whose lastHeartbeatAt is older "
                            + "than now - this duration are pruned.",
                    defaultValue = "2m")
            Duration staleAfter,
            @Option(longName = "probe",
                    description = "Additionally prune pods whose endpoint responds with a "
                            + "different podId (live identity mismatch). Requires the "
                            + "--tenant key for the ping JWT.",
                    defaultValue = "false")
            boolean probe,
            @Option(longName = "tenant", shortName = 'T',
                    description = "Tenant whose JWT key signs the probe ping.",
                    defaultValue = TenantService.SYSTEM_TENANT)
            String tenant,
            @Option(longName = "apply",
                    description = "Actually delete the rows. Default is dry-run.",
                    defaultValue = "false")
            boolean apply) {
        List<BrainPodDocument> pods = loadPods(cluster);
        if (pods.isEmpty()) {
            return cluster == null ? "(no pods registered)"
                    : "(no pods registered in cluster '" + cluster + "')";
        }
        Instant now = Instant.now();
        List<PruneRow> rows = new ArrayList<>();
        for (BrainPodDocument pod : pods) {
            @Nullable String reason = pruneReason(pod, now, staleAfter, probe, tenant);
            if (reason != null) {
                rows.add(new PruneRow(pod, reason));
            }
        }
        if (rows.isEmpty()) {
            return "Nothing to prune (scanned " + pods.size() + " pod"
                    + (pods.size() == 1 ? "" : "s") + ").";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Tables.render(
                List.of("CLUSTER", "NODE", "PODID", "ENDPOINT", "REASON"),
                List.<Function<PruneRow, @Nullable Object>>of(
                        r -> r.pod.getClusterId(),
                        r -> r.pod.getNodeName(),
                        r -> truncate(r.pod.getPodId(), 12),
                        r -> r.pod.getEndpoint(),
                        r -> r.reason),
                rows));
        if (!apply) {
            sb.append("\nDRY-RUN — re-run with --apply to actually delete ")
                    .append(rows.size()).append(" row").append(rows.size() == 1 ? "" : "s")
                    .append('.');
            return sb.toString();
        }
        long deleted = 0;
        for (PruneRow row : rows) {
            deleted += brainPodService.deleteByPodId(row.pod.getPodId());
        }
        sb.append("\nDeleted ").append(deleted).append(" row")
                .append(deleted == 1 ? "" : "s").append('.');
        return sb.toString();
    }

    /**
     * Returns a non-null prune reason if the pod should be removed,
     * {@code null} otherwise. Stale heartbeat is checked first (cheap);
     * the optional live probe runs only for pods that pass the staleness
     * check, so an offline-friendly prune doesn't make any HTTP calls.
     */
    private @Nullable String pruneReason(
            BrainPodDocument pod, Instant now, Duration staleAfter,
            boolean probe, String tenant) {
        Instant beat = pod.getLastHeartbeatAt();
        if (beat == null) {
            // No heartbeat ever recorded. Could mean "fresh registration,
            // hasn't ticked yet" — staleAfter gives us a grace window
            // anchored on bootedAt instead.
            Instant booted = pod.getBootedAt();
            if (booted != null && booted.isBefore(now.minus(staleAfter))) {
                return "no heartbeat (booted " + booted + ")";
            }
            return null;
        }
        if (beat.isBefore(now.minus(staleAfter))) {
            return "stale heartbeat (" + beat + ")";
        }
        if (probe) {
            PingRow ping = pingOne(pod, tenant);
            if ("STALE".equals(ping.result)) {
                return "live mismatch (" + ping.detail + ")";
            }
            if ("ERROR".equals(ping.result)) {
                return "unreachable (" + ping.detail + ")";
            }
        }
        return null;
    }

    private record PruneRow(BrainPodDocument pod, String reason) {
    }

    @Command(name = {"cluster", "ping"},
            description = "End-to-end JWT ping against every pod (or a single --cluster). "
                    + "Mints a fresh _vance-admin token for the --tenant and calls "
                    + "GET /brain/{tenant}/admin/ping on each pod's own endpoint. "
                    + "Result is OK only when the responding podId matches the DB row; "
                    + "STALE means another pod has taken over the address.")
    public String ping(
            @Option(longName = "cluster", shortName = 'c',
                    description = "Cluster id to filter by. Omit to ping every cluster.")
            @Nullable String cluster,
            @Option(longName = "tenant", shortName = 'T',
                    description = "Tenant whose JWT key signs the ping token. Defaults to '" + TenantService.SYSTEM_TENANT + "'.",
                    defaultValue = TenantService.SYSTEM_TENANT)
            String tenant) {
        List<BrainPodDocument> pods = loadPods(cluster);
        if (pods.isEmpty()) {
            return cluster == null ? "(no pods registered — nothing to ping)"
                    : "(no pods registered in cluster '" + cluster + "')";
        }
        List<PingRow> rows = new ArrayList<>(pods.size());
        for (BrainPodDocument pod : pods) {
            rows.add(pingOne(pod, tenant));
        }
        return Tables.render(
                List.of("CLUSTER", "NODE", "ENDPOINT", "RESULT", "LATENCY", "DETAIL"),
                List.<Function<PingRow, @Nullable Object>>of(
                        r -> r.pod.getClusterId(),
                        r -> r.pod.getNodeName(),
                        r -> r.pod.getEndpoint(),
                        r -> r.result,
                        r -> r.latency == null ? "" : r.latency.toMillis() + "ms",
                        r -> r.detail),
                rows);
    }

    private PingRow pingOne(BrainPodDocument pod, String tenant) {
        String endpoint = pod.getEndpoint();
        if (StringUtils.isBlank(endpoint)) {
            return new PingRow(pod, "SKIP", null, "no endpoint advertised");
        }
        String baseUrl = endpoint.startsWith("http://") || endpoint.startsWith("https://")
                ? endpoint
                : "http://" + endpoint;
        String path = "/brain/" + tenant + "/admin/ping";
        Instant start = Instant.now();
        try {
            Response response = brainClient.getAt(baseUrl, tenant, path);
            Duration latency = Duration.between(start, Instant.now());
            if (!response.isSuccess()) {
                return new PingRow(pod, "HTTP " + response.statusCode(), latency,
                        truncate(response.body(), 80));
            }
            // Identity check: an HTTP 200 only proves "something is on this
            // address". Compare the responding podId with the DB row to
            // catch the common case of a fresh boot reusing the host:port
            // from an old, never-cleaned brain_pods row.
            String respondingPodId = extractValue(response.body(), "podId");
            String respondingNodeName = extractValue(response.body(), "nodeName");
            if (!pod.getPodId().equals(respondingPodId)) {
                return new PingRow(pod, "STALE", latency,
                        "answered by '" + respondingNodeName
                                + "' (podId=" + truncate(respondingPodId, 8) + "…)");
            }
            return new PingRow(pod, "OK", latency, "served by " + respondingNodeName);
        } catch (BrainCallException e) {
            return new PingRow(pod, "ERROR", Duration.between(start, Instant.now()),
                    truncate(e.getMessage(), 80));
        }
    }

    private List<BrainPodDocument> loadPods(@Nullable String cluster) {
        List<BrainPodDocument> pods = (cluster != null && !cluster.isBlank())
                ? brainPodService.listCluster(cluster)
                : brainPodService.listAll();
        return sort(pods);
    }

    private static List<BrainPodDocument> sort(List<BrainPodDocument> pods) {
        return pods.stream()
                .sorted(Comparator.comparing(BrainPodDocument::getClusterId)
                        .thenComparing(BrainPodDocument::getNodeName))
                .toList();
    }

    private static String extractValue(String body, String key) {
        int idx = body.indexOf("\"" + key + "\"");
        if (idx < 0) return "?";
        int colon = body.indexOf(':', idx);
        if (colon < 0) return "?";
        int firstQuote = body.indexOf('"', colon);
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) return "?";
        return body.substring(firstQuote + 1, secondQuote);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private record PingRow(
            BrainPodDocument pod,
            String result,
            @Nullable Duration latency,
            String detail) {
    }
}
