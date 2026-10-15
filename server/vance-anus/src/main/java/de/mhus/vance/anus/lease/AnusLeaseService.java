package de.mhus.vance.anus.lease;

import de.mhus.vance.anus.brain.AnusBrainClient;
import de.mhus.vance.anus.brain.AnusBrainClient.Response;
import de.mhus.vance.shared.lease.LeaseCounter;
import de.mhus.vance.shared.lease.LeaseDefinition;
import de.mhus.vance.shared.lease.LeaseDefinitionLoader;
import de.mhus.vance.shared.lease.ResourceLeaseHolderDocument;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lease operations of the admin shell. Reads go straight to Mongo through
 * the shared {@link ResourceLeaseService}; revokes go to the brain,
 * because only the brain has the handler registry and the pod routing
 * that turn a freed slot into a cleaned-up resource.
 */
@Service
public class AnusLeaseService {

    private static final String ADMIN_PATH = "/internal/lease/admin";

    private final ResourceLeaseService leaseService;
    private final LeaseDefinitionLoader definitionLoader;
    private final AnusBrainClient brainClient;

    /** Own instance — anus runs without web auto-configuration, no Jackson 3 mapper bean. */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public AnusLeaseService(
            ResourceLeaseService leaseService, LeaseDefinitionLoader definitionLoader, AnusBrainClient brainClient) {
        this.leaseService = leaseService;
        this.definitionLoader = definitionLoader;
        this.brainClient = brainClient;
    }

    // ─── reads ──────────────────────────────────────────────────────────────

    /** Holder filter; every {@code null} field matches all. */
    public record HolderFilter(
            @Nullable String tenantId,
            @Nullable String resource,
            @Nullable String pod,
            @Nullable String type) {

        boolean matches(ResourceLeaseHolderDocument h) {
            return (tenantId == null || tenantId.equals(h.getTenantId()))
                    && (resource == null || resource.equals(h.getResource()))
                    && (pod == null || pod.equals(h.getPod()))
                    && (type == null || type.equals(h.getType()));
        }
    }

    /** Holders matching the filter, oldest first. */
    public List<ResourceLeaseHolderDocument> holders(HolderFilter filter) {
        return leaseService.listHolders().stream()
                .filter(filter::matches)
                .sorted(Comparator.comparing(
                        ResourceLeaseHolderDocument::getAcquiredAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public Optional<ResourceLeaseHolderDocument> holder(String token) {
        return leaseService.findHolder(token);
    }

    /**
     * One counter with the number of holders that book it — the value
     * reconcile corrects towards; a difference is drift.
     */
    public record CounterRow(LeaseCounter counter, long booked) {

        public boolean drifted() {
            return counter.count() != booked;
        }
    }

    /** Counters, optionally narrowed to one tenant (which drops the global level) and one resource. */
    public List<CounterRow> counters(@Nullable String tenantId, @Nullable String resource) {
        Map<String, Long> booked = new HashMap<>();
        for (ResourceLeaseHolderDocument holder : leaseService.listHolders()) {
            for (String key : holder.getLevels()) {
                booked.merge(key, 1L, Long::sum);
            }
        }
        List<LeaseCounter> counters =
                tenantId == null ? leaseService.listCounters() : leaseService.listCounters(tenantId);
        return counters.stream()
                .filter(c -> resource == null || resource.equals(c.resource()))
                .sorted(Comparator.comparing(LeaseCounter::key))
                .map(c -> new CounterRow(c, booked.getOrDefault(c.key(), 0L)))
                .toList();
    }

    /**
     * The merged definition as an acquisition at this scope would see it.
     * The loader's cache is dropped first: anus is not on the
     * document-change-event chain, so a cached entry could predate an edit
     * made since — and an operator checking a definition is checking the
     * current one.
     */
    public Optional<LeaseDefinition> definition(String tenantId, @Nullable String projectId, String name) {
        definitionLoader.invalidateAll();
        return definitionLoader.load(tenantId, projectId, name);
    }

    /** Corrects counter drift on counters quiet since {@code safetyNotBefore}. No handler involved. */
    public long reconcile(Instant safetyNotBefore) {
        return leaseService.reconcile(safetyNotBefore);
    }

    // ─── revokes ────────────────────────────────────────────────────────────

    /** Outcome of a revoke; {@code detail} carries the brain's answer on failure. */
    public record RevokeOutcome(Status status, List<String> tokens, String detail) {

        public enum Status {
            /** Revoked, handler dispatched by the brain. */
            REVOKED,
            /** Revoked straight in Mongo — no handler ran. */
            FORCED,
            /** Nothing to revoke. */
            NOT_FOUND,
            /** The brain answered with an error. */
            FAILED
        }
    }

    /**
     * Revokes one holder. Without {@code force} through the brain (handler
     * runs); with {@code force} straight in Mongo — the slot is free, the
     * holder fails its next check, but a resource in a foreign system
     * (remote MCP session of a dead pod) is left to its own expiry.
     */
    public RevokeOutcome revoke(String token, boolean force) {
        if (force) {
            return leaseService
                    .revoke(token, null)
                    .map(h -> new RevokeOutcome(RevokeOutcome.Status.FORCED, List.of(h.getToken()), ""))
                    .orElseGet(() -> new RevokeOutcome(RevokeOutcome.Status.NOT_FOUND, List.of(), ""));
        }
        return brainRevoke(ADMIN_PATH + "/holders/" + encode(token) + "/revoke");
    }

    /** Revokes every holder of one pod; {@code force} as in {@link #revoke}. */
    public RevokeOutcome revokePod(String podId, boolean force) {
        if (force) {
            List<String> tokens = leaseService.reapPod(podId).stream()
                    .map(ResourceLeaseHolderDocument::getToken)
                    .toList();
            return new RevokeOutcome(
                    tokens.isEmpty() ? RevokeOutcome.Status.NOT_FOUND : RevokeOutcome.Status.FORCED, tokens, "");
        }
        return brainRevoke(ADMIN_PATH + "/pods/" + encode(podId) + "/revoke");
    }

    /** One reaper round on a brain now. {@code null} on success, otherwise the brain's answer. */
    public @Nullable String sweep() {
        Response response = brainClient.internal(ADMIN_PATH + "/sweep", "POST", null);
        return response.isSuccess() ? null : "HTTP " + response.statusCode() + " " + response.body();
    }

    private RevokeOutcome brainRevoke(String path) {
        Response response = brainClient.internal(path, "POST", null);
        if (response.statusCode() == 404) {
            return new RevokeOutcome(RevokeOutcome.Status.NOT_FOUND, List.of(), "");
        }
        if (!response.isSuccess()) {
            return new RevokeOutcome(
                    RevokeOutcome.Status.FAILED, List.of(), "HTTP " + response.statusCode() + " " + response.body());
        }
        List<String> tokens = revokedTokens(response.body());
        return new RevokeOutcome(
                tokens.isEmpty() ? RevokeOutcome.Status.NOT_FOUND : RevokeOutcome.Status.REVOKED, tokens, "");
    }

    /** Tokens out of the brain's {@code {"revoked":[{"token":…},…]}} answer. */
    List<String> revokedTokens(String body) {
        if (StringUtils.isBlank(body)) return List.of();
        List<String> tokens = new ArrayList<>();
        for (JsonNode holder : objectMapper.readTree(body).path("revoked")) {
            String token = holder.path("token").asString("");
            if (!token.isEmpty()) tokens.add(token);
        }
        return tokens;
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
