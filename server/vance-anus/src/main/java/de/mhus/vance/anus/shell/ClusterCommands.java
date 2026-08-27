package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.cluster.PodClusterService;
import de.mhus.vance.anus.cluster.PodClusterService.PodPing;
import de.mhus.vance.anus.cluster.PodClusterService.PruneCandidate;
import de.mhus.vance.shared.cluster.BrainPodCapacity;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
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

    private final PodClusterService podService;
    /**
     * Own instance — anus runs without Spring Boot's web auto-configuration, so
     * there is no auto-registered Jackson 3 mapper bean to inject. Same reason
     * and same shape as {@code ProjectKitsCommands}.
     */

    @Command(name = {"cluster", "list"},
            description = "List registered brain pods. Filter by --cluster (default: all clusters). "
                    + "The MASTER column carries '*' for the pod that currently holds the "
                    + "cluster-master lease — expired/absent leases leave the column blank.")
    public String list(
            @Option(longName = "cluster", shortName = 'c',
                    description = "Cluster id to filter by. Omit to list every pod regardless of cluster.")
            @Nullable String cluster) {
        List<BrainPodDocument> pods = podService.pods(cluster);
        if (pods.isEmpty()) {
            return cluster == null ? "(no pods registered)"
                    : "(no pods registered in cluster '" + cluster + "')";
        }
        Map<String, String> liveMasterPodIdByCluster = podService.liveMasterPodIds(pods);
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
        var report = podService.demand(tenant);
        if (!report.success()) {
            return "(failed: HTTP " + report.statusCode() + " " + report.body() + ")";
        }
        return report.body();
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
            return patchPod(doc, PodClusterService.labelsWith(doc, parsePairs(labels)),
                    null, null, false);
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
            var removal = PodClusterService.labelsWithout(doc, splitCsv(keys));
            List<String> missing = removal.keysNotFound();
            String result = patchPod(doc, removal.labels(), null, null, false);
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
     * The echo an operator sees after a placement change. The wire shape lives
     * in {@link PodClusterService#patch}; what is left here is the wording and
     * the pod's display name, which the service does not deal in.
     */
    private String patchPod(
            BrainPodDocument doc,
            @Nullable Map<String, String> labels,
            @Nullable Boolean exclusive,
            @Nullable Integer maxScoreOverride,
            boolean clearOverride) {
        var patched = podService.patch(
                doc.getPodId(), labels, exclusive, maxScoreOverride, clearOverride);
        if (!patched.success()) {
            return "(failed: HTTP " + patched.statusCode() + " " + patched.detail() + ")";
        }
        return "pod '" + doc.getNodeName() + "' → " + patched.detail();
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
            return action.apply(podService.resolve(podOrNode));
        } catch (IllegalArgumentException e) {
            // Covers the pod lookup and the k=v grammar inside the action —
            // both are the caller mistyping an argument.
            return "(" + e.getMessage() + ")";
        }
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
        List<BrainPodDocument> pods = podService.pods(cluster);
        if (pods.isEmpty()) {
            return cluster == null ? "(no pods registered)"
                    : "(no pods registered in cluster '" + cluster + "')";
        }
        var candidates = podService.pruneCandidates(pods, staleAfter, probe, tenant);
        if (candidates.isEmpty()) {
            return "Nothing to prune (scanned " + pods.size() + " pod"
                    + (pods.size() == 1 ? "" : "s") + ").";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Tables.render(
                List.of("CLUSTER", "NODE", "PODID", "ENDPOINT", "REASON"),
                List.<Function<PruneCandidate, @Nullable Object>>of(
                        c -> c.pod().getClusterId(),
                        c -> c.pod().getNodeName(),
                        c -> truncate(c.pod().getPodId(), 12),
                        c -> c.pod().getEndpoint(),
                        ClusterCommands::pruneReasonText),
                candidates));
        // The dry-run stays here: the service finds candidates and deletes them,
        // and whether to delete is this command's decision, not its own.
        if (!apply) {
            sb.append("\nDRY-RUN — re-run with --apply to actually delete ")
                    .append(candidates.size()).append(" row")
                    .append(candidates.size() == 1 ? "" : "s")
                    .append('.');
            return sb.toString();
        }
        long deleted = podService.prune(candidates);
        sb.append("\nDeleted ").append(deleted).append(" row")
                .append(deleted == 1 ? "" : "s").append('.');
        return sb.toString();
    }

    /**
     * The REASON cell. The two probe reasons reuse {@link #pingDetailText} — a
     * mismatch is the same fact here as in the ping table, and formatting it
     * twice is how the two would drift apart.
     */
    static String pruneReasonText(PruneCandidate candidate) {
        return switch (candidate.reason()) {
            case NO_HEARTBEAT -> "no heartbeat (booted " + candidate.detail() + ")";
            case STALE_HEARTBEAT -> "stale heartbeat (" + candidate.detail() + ")";
            case LIVE_MISMATCH -> "live mismatch ("
                    + pingDetailText(requireProbe(candidate)) + ")";
            case UNREACHABLE -> "unreachable ("
                    + pingDetailText(requireProbe(candidate)) + ")";
        };
    }

    /** A probe reason without its probe is a broken invariant, not a case. */
    private static PodPing requireProbe(PruneCandidate candidate) {
        return java.util.Objects.requireNonNull(
                candidate.ping(), "a probe-derived prune reason carries its ping");
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
        List<BrainPodDocument> pods = podService.pods(cluster);
        if (pods.isEmpty()) {
            return cluster == null ? "(no pods registered — nothing to ping)"
                    : "(no pods registered in cluster '" + cluster + "')";
        }
        return Tables.render(
                List.of("CLUSTER", "NODE", "ENDPOINT", "RESULT", "LATENCY", "DETAIL"),
                List.<Function<PodPing, @Nullable Object>>of(
                        r -> r.pod().getClusterId(),
                        r -> r.pod().getNodeName(),
                        r -> r.pod().getEndpoint(),
                        ClusterCommands::pingResultText,
                        r -> r.latency() == null ? "" : r.latency().toMillis() + "ms",
                        ClusterCommands::pingDetailText),
                podService.ping(pods, tenant));
    }

    /**
     * The RESULT cell. {@code HTTP_ERROR} carries its status because "HTTP" on
     * its own says nothing an operator can act on — which is why the service
     * hands the code over separately instead of baking it into a label.
     */
    static String pingResultText(PodPing ping) {
        return switch (ping.result()) {
            case OK -> "OK";
            case STALE -> "STALE";
            case UNREACHABLE -> "ERROR";
            case SKIPPED -> "SKIP";
            case HTTP_ERROR -> "HTTP " + ping.statusCode();
        };
    }

    /** The DETAIL cell. */
    static String pingDetailText(PodPing ping) {
        return switch (ping.result()) {
            case OK -> "served by " + ping.respondingNodeName();
            case STALE -> "answered by '" + ping.respondingNodeName()
                    + "' (podId=" + truncate(ping.detail(), 8) + "…)";
            case UNREACHABLE, HTTP_ERROR -> truncate(ping.detail(), 80);
            case SKIPPED -> ping.detail();
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
