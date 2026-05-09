package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.BrainCallException;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.cluster.BrainPodService;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * Cluster-side admin: list pods registered in {@code brain_pods}, and
 * fire an authenticated end-to-end ping against each one. The ping is
 * the canonical proof that the JWT path works — token mint, bearer
 * header, server-side filter, controller, all in one round-trip.
 */
@ShellComponent
@RequiresAuth
@RequiredArgsConstructor
public class ClusterCommands {

    private final BrainPodService brainPodService;
    private final AnusBrainClient brainClient;

    @ShellMethod(key = "cluster list",
            value = "List registered brain pods. Filter by --cluster (default: all clusters).")
    public String list(
            @ShellOption(value = {"--cluster", "-c"}, defaultValue = ShellOption.NULL,
                    help = "Cluster id to filter by. Omit to list every pod regardless of cluster.")
            @Nullable String cluster) {
        List<BrainPodDocument> pods = loadPods(cluster);
        if (pods.isEmpty()) {
            return cluster == null ? "(no pods registered)"
                    : "(no pods registered in cluster '" + cluster + "')";
        }
        return Tables.render(
                List.of("CLUSTER", "NODE", "PODID", "ENDPOINT", "STATUS", "VERSION", "LASTBEAT"),
                List.<Function<BrainPodDocument, @Nullable Object>>of(
                        BrainPodDocument::getClusterId,
                        BrainPodDocument::getNodeName,
                        BrainPodDocument::getPodId,
                        BrainPodDocument::getEndpoint,
                        BrainPodDocument::getStatus,
                        BrainPodDocument::getVersion,
                        BrainPodDocument::getLastHeartbeatAt),
                pods);
    }

    @ShellMethod(key = "cluster prune",
            value = "List (or remove with --apply) brain_pods rows that are stale or "
                    + "respond as a different podId. Default is dry-run.")
    public String prune(
            @ShellOption(value = {"--cluster", "-c"}, defaultValue = ShellOption.NULL,
                    help = "Cluster id to filter by. Omit to scan every cluster.")
            @Nullable String cluster,
            @ShellOption(value = {"--stale-after"}, defaultValue = "2m",
                    help = "Heartbeat threshold. Pods whose lastHeartbeatAt is older "
                            + "than now - this duration are pruned.")
            Duration staleAfter,
            @ShellOption(value = {"--probe"}, defaultValue = "false",
                    help = "Additionally prune pods whose endpoint responds with a "
                            + "different podId (live identity mismatch). Requires the "
                            + "--tenant key for the ping JWT.")
            boolean probe,
            @ShellOption(value = {"--tenant", "-T"}, defaultValue = TenantService.SYSTEM_TENANT,
                    help = "Tenant whose JWT key signs the probe ping.")
            String tenant,
            @ShellOption(value = {"--apply"}, defaultValue = "false",
                    help = "Actually delete the rows. Default is dry-run.")
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

    @ShellMethod(key = "cluster ping",
            value = "End-to-end JWT ping against every pod (or a single --cluster). "
                    + "Mints a fresh _vance-admin token for the --tenant and calls "
                    + "GET /brain/{tenant}/admin/ping on each pod's own endpoint. "
                    + "Result is OK only when the responding podId matches the DB row; "
                    + "STALE means another pod has taken over the address.")
    public String ping(
            @ShellOption(value = {"--cluster", "-c"}, defaultValue = ShellOption.NULL,
                    help = "Cluster id to filter by. Omit to ping every cluster.")
            @Nullable String cluster,
            @ShellOption(value = {"--tenant", "-T"}, defaultValue = TenantService.SYSTEM_TENANT,
                    help = "Tenant whose JWT key signs the ping token. Defaults to '" + TenantService.SYSTEM_TENANT + "'.")
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
