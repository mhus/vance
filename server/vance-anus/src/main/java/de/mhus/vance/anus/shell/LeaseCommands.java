package de.mhus.vance.anus.shell;

import de.mhus.vance.anus.access.RequiresAuth;
import de.mhus.vance.anus.lease.AnusLeaseService;
import de.mhus.vance.anus.lease.AnusLeaseService.CounterRow;
import de.mhus.vance.anus.lease.AnusLeaseService.HolderFilter;
import de.mhus.vance.anus.lease.AnusLeaseService.RevokeOutcome;
import de.mhus.vance.shared.lease.LeaseDefinition;
import de.mhus.vance.shared.lease.LeaseUnavailableException;
import de.mhus.vance.shared.lease.ResourceLeaseHolderDocument;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Resource leases across all tenants ({@code specification/public/lease-system.md}
 * §9): holders, counters, definitions, revoke, a reaper round on demand and
 * counter reconcile. Reads come straight from Mongo; revokes go through a brain
 * so the lease type's handler runs, with {@code --force} as the way out when no
 * brain is reachable.
 */
@Component
@RequiresAuth
@RequiredArgsConstructor
public class LeaseCommands {

    private final AnusLeaseService leases;

    @Command(
            name = {"lease", "list"},
            description = "List held resource leases, oldest first. All filters are optional and combine.")
    public String list(
            @Option(longName = "tenant", shortName = 'T', description = "Only holders of this tenant.") @Nullable
                    String tenant,
            @Option(longName = "resource", shortName = 'r', description = "Only this resource key.") @Nullable
                    String resource,
            @Option(longName = "pod", shortName = 'p', description = "Only holders of this pod id.") @Nullable
                    String pod,
            @Option(longName = "type", shortName = 't', description = "Only this lease type (tex, mcp, …).") @Nullable
                    String type) {
        List<ResourceLeaseHolderDocument> holders = leases.holders(
                new HolderFilter(blankToNull(tenant), blankToNull(resource), blankToNull(pod), blankToNull(type)));
        if (holders.isEmpty()) {
            return "(no leases held)";
        }
        Instant now = Instant.now();
        return Tables.render(
                List.of(
                        "TOKEN",
                        "TYPE",
                        "RESOURCE",
                        "TENANT",
                        "PROJECT",
                        "USER",
                        "POD",
                        "BOUND",
                        "STATE",
                        "AGE",
                        "IDLE",
                        "TTL"),
                List.<Function<ResourceLeaseHolderDocument, @Nullable Object>>of(
                        ResourceLeaseHolderDocument::getToken,
                        ResourceLeaseHolderDocument::getType,
                        ResourceLeaseHolderDocument::getResource,
                        ResourceLeaseHolderDocument::getTenantId,
                        ResourceLeaseHolderDocument::getProjectId,
                        ResourceLeaseHolderDocument::getUserId,
                        ResourceLeaseHolderDocument::getPod,
                        h -> h.isPodBound() ? "yes" : "",
                        ResourceLeaseHolderDocument::getState,
                        h -> since(h.getAcquiredAt(), now),
                        h -> since(h.getLastSeenAt(), now),
                        h -> h.getTtlSeconds() + "s"),
                holders);
    }

    @Command(
            name = {"lease", "show"},
            description = "Everything about one held lease, including the booked levels and the cleanup payload.")
    public String show(
            @Option(longName = "token", shortName = 'k', required = true, description = "Holder token (rl-…).")
                    String token) {
        return leases.holder(token).map(LeaseCommands::renderHolder).orElse("(no lease '" + token + "')");
    }

    static String renderHolder(ResourceLeaseHolderDocument h) {
        StringBuilder out = new StringBuilder();
        out.append("token       ").append(h.getToken()).append('\n');
        out.append("state       ").append(h.getState()).append('\n');
        out.append("type        ").append(h.getType()).append('\n');
        out.append("resource    ").append(h.getResource()).append('\n');
        out.append("holderRef   ").append(h.getHolderRef()).append('\n');
        out.append("tenant      ").append(h.getTenantId()).append('\n');
        out.append("project     ").append(nullToDash(h.getProjectId())).append('\n');
        out.append("user        ").append(nullToDash(h.getUserId())).append('\n');
        out.append("pod         ")
                .append(h.getPod())
                .append(h.isPodBound() ? "  (pod-bound — the resource dies with the pod)" : "")
                .append('\n');
        out.append("acquired    ").append(nullToDash(h.getAcquiredAt())).append('\n');
        out.append("lastSeen    ").append(nullToDash(h.getLastSeenAt())).append('\n');
        out.append("ttl         ").append(h.getTtlSeconds()).append("s\n");
        out.append("levels      ")
                .append(h.getLevels().isEmpty() ? "(none)" : String.join(", ", h.getLevels()))
                .append('\n');
        Map<String, Object> payload = h.getPayload() == null ? Map.of() : new TreeMap<>(h.getPayload());
        out.append("payload     ").append(payload.isEmpty() ? "(none)" : "").append('\n');
        payload.forEach(
                (k, v) -> out.append("  ").append(k).append(" = ").append(v).append('\n'));
        return out.toString();
    }

    @Command(
            name = {"lease", "counters"},
            description = "Funnel counters with the number of holders that book them. A '!' in DRIFT marks "
                    + "a counter reconcile would correct.")
    public String counters(
            @Option(
                            longName = "tenant",
                            shortName = 'T',
                            description =
                                    "Only this tenant's levels (tenant/project/user). Omit for all, including global.")
                    @Nullable
                    String tenant,
            @Option(longName = "resource", shortName = 'r', description = "Only this resource key.") @Nullable
                    String resource) {
        List<CounterRow> rows = leases.counters(blankToNull(tenant), blankToNull(resource));
        if (rows.isEmpty()) {
            return "(no counters)";
        }
        return Tables.render(
                List.of("RESOURCE", "LEVEL", "SCOPE", "COUNT", "BOOKED", "DRIFT", "UPDATED"),
                List.<Function<CounterRow, @Nullable Object>>of(
                        r -> r.counter().resource(),
                        r -> r.counter().level(),
                        r -> r.counter().scope(),
                        r -> r.counter().count(),
                        CounterRow::booked,
                        r -> r.drifted() ? "!" : "",
                        r -> r.counter().updatedAt()),
                rows);
    }

    @Command(
            name = {"lease", "definition"},
            description = "The merged lease definition (classpath → system → tenant → project) as an "
                    + "acquisition in this scope would see it.")
    public String definition(
            @Option(longName = "name", shortName = 'n', required = true, description = "Lease name.") String name,
            @Option(longName = "tenant", shortName = 'T', required = true, description = "Tenant scope.") String tenant,
            @Option(longName = "project", shortName = 'P', description = "Project scope. Omit for the tenant view.")
                    @Nullable
                    String project) {
        try {
            return leases.definition(tenant, blankToNull(project), name)
                    .map(LeaseCommands::renderDefinition)
                    .orElse("(lease '" + name + "' is not defined in any layer)");
        } catch (LeaseUnavailableException e) {
            // Invalid name, unreadable or malformed layer — the message names which.
            return "(" + e.getMessage() + ")";
        }
    }

    static String renderDefinition(LeaseDefinition d) {
        StringBuilder out = new StringBuilder();
        out.append("name        ").append(d.name()).append('\n');
        out.append("resource    ").append(d.resourceKey()).append('\n');
        out.append("global      ").append(cap(d.maxGlobal())).append('\n');
        out.append("tenant      ").append(cap(d.maxTenant())).append('\n');
        out.append("project     ").append(cap(d.maxProject())).append('\n');
        out.append("user        ").append(cap(d.maxUser())).append('\n');
        out.append("ttlSeconds  ")
                .append(d.ttlSecondsOrDefault())
                .append(d.ttlSeconds() == null ? "  (default)" : "")
                .append('\n');
        out.append("waitMs      ")
                .append(d.waitMsOrDefault())
                .append(d.waitMs() == null ? "  (default)" : "")
                .append('\n');
        if (!d.hasAnyCap()) {
            out.append("(no cap on any level — acquisitions get a NOOP lease)\n");
        }
        return out.toString();
    }

    @Command(
            name = {"lease", "revoke"},
            description = "Revoke one lease. Goes through a brain, which runs the lease type's cleanup handler. "
                    + "--force frees the slot straight in Mongo when no brain is reachable — no handler runs.")
    public String revoke(
            @Option(longName = "token", shortName = 'k', required = true, description = "Holder token (rl-…).")
                    String token,
            @Option(
                            longName = "force",
                            description = "Revoke in Mongo without a brain; no cleanup handler.",
                            defaultValue = "false")
                    boolean force) {
        return BrainCalls.text(() -> renderRevoke(leases.revoke(token, force), "lease '" + token + "'"));
    }

    @Command(
            name = {"lease", "revoke-pod"},
            description = "Revoke every lease held by one pod. Same brain/--force split as 'lease revoke'.")
    public String revokePod(
            @Option(longName = "pod", shortName = 'p', required = true, description = "Pod id of the holders.")
                    String pod,
            @Option(
                            longName = "force",
                            description = "Revoke in Mongo without a brain; no cleanup handler.",
                            defaultValue = "false")
                    boolean force) {
        return BrainCalls.text(() -> renderRevoke(leases.revokePod(pod, force), "leases of pod '" + pod + "'"));
    }

    static String renderRevoke(RevokeOutcome outcome, String subject) {
        return switch (outcome.status()) {
            case REVOKED ->
                "Revoked " + count(outcome.tokens()) + ", cleanup handler dispatched: "
                        + String.join(", ", outcome.tokens());
            case FORCED ->
                "Revoked " + count(outcome.tokens()) + " without a cleanup handler: "
                        + String.join(", ", outcome.tokens())
                        + "\nThe holder fails its next check; a resource in a foreign system is left to its own expiry.";
            case NOT_FOUND -> "(no " + subject + " to revoke)";
            case FAILED -> "(failed: " + outcome.detail() + ")";
        };
    }

    @Command(
            name = {"lease", "sweep"},
            description = "Run one reaper round on a brain now: idle reap, dead-pod reap, counter reconcile — "
                    + "with handler dispatch, like the scheduled round.")
    public String sweep() {
        return BrainCalls.text(() -> {
            String failure = leases.sweep();
            return failure == null ? "Reaper round done." : "(failed: " + failure + ")";
        });
    }

    @Command(
            name = {"lease", "reconcile"},
            description = "Correct counter drift from the holders, in Mongo, no brain needed. Counters written "
                    + "within the safety window are left alone — an acquisition may be running.")
    public String reconcile(
            @Option(
                            longName = "safety",
                            description = "Only counters quiet for this long. '60s' or 'PT1M'.",
                            defaultValue = "60s")
                    String safety) {
        // Parsed here, not bound: Spring Shell has no String→Duration converter
        // (same reason as cluster prune --stale-after).
        Duration window;
        try {
            window = DurationStyle.detectAndParse(safety);
        } catch (IllegalArgumentException e) {
            return "(--safety must be a duration like '60s' or 'PT1M', got '" + safety + "')";
        }
        long corrected = leases.reconcile(Instant.now().minus(window));
        return "Corrected " + corrected + " counter" + (corrected == 1 ? "" : "s") + ".";
    }

    private static String count(List<String> tokens) {
        return tokens.size() + " lease" + (tokens.size() == 1 ? "" : "s");
    }

    private static String cap(@Nullable Long cap) {
        return cap == null ? "—  (no cap)" : cap.toString();
    }

    static String since(@Nullable Instant at, Instant now) {
        if (at == null) return "";
        long s = Math.max(0, Duration.between(at, now).getSeconds());
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86_400) return (s / 3600) + "h" + ((s % 3600) / 60) + "m";
        return (s / 86_400) + "d" + ((s % 86_400) / 3600) + "h";
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private static String nullToDash(@Nullable Object value) {
        return value == null ? "—" : value.toString();
    }
}
