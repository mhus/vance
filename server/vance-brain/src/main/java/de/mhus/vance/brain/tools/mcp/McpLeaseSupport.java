package de.mhus.vance.brain.tools.mcp;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.Lease;
import de.mhus.vance.shared.lease.LeaseDefinition;
import de.mhus.vance.shared.lease.LeaseRequest;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.mcp.McpConfig;
import de.mhus.vance.toolpack.mcp.SessionGuard;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Builds the lease-backed {@link SessionGuard} for an {@code mcp_server}
 * tool document and registers the matching handler type ({@code mcp}).
 *
 * <p>The guard implements the session lifecycle table of
 * {@code planning/lease-system.md} §10: acquire at {@code ensureInitialized}
 * (session open), payload update with the endpoint + server session id
 * after {@code initialize}, check-at-use on every {@code tools/call},
 * release on {@code close}.
 *
 * <p>{@code podBound} follows the transport: a stdio subprocess dies with
 * the pod (true), an HTTP session lives in the remote server (false — the
 * reaper-pod's handler has to DELETE it with the session id from the
 * payload when the holder pod is gone).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class McpLeaseSupport {

    /** Parameter name on the {@code mcp_server} tool config. */
    public static final String PARAM_LEASE = "lease";

    private final ResourceLeaseService leaseService;
    private final ClusterService cluster;

    /**
     * The guard for one connection, or {@code null} when the tool config
     * has no {@code lease} parameter (no lease, no Mongo traffic).
     *
     * @param doc          the server-tool document (source of the lease parameter)
     * @param cfg          the parsed MCP config (transport decides podBound, endpoint description)
     * @param ctx          the connection's bootstrap context (scope of the lease)
     * @param connectionKey pool entry key — holderRef, the handler maps it back to the connection
     */
    public @Nullable SessionGuard createGuard(
            ServerToolDocument doc, McpConfig cfg, ToolInvocationContext ctx, String connectionKey) {
        Object param = doc.getParameters() == null ? null : doc.getParameters().get(PARAM_LEASE);
        if (param == null) return null;

        LeaseRequest.Builder builder = LeaseRequest.builder()
                .type(McpLeaseHandler.TYPE)
                .tenantId(Objects.requireNonNullElse(ctx.tenantId(), ""))
                .projectId(ctx.projectId())
                .userId(ctx.userId())
                .holderRef(connectionKey)
                .pod(cluster.selfPodId())
                .podBound(cfg.transport() == McpConfig.Transport.STDIO)
                .payload(initialPayload(cfg, connectionKey));

        if (param instanceof String name) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) return null;
            builder.lease(trimmed);
        } else if (param instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) raw;
            Object nameObj = map.get("name");
            if (nameObj == null || String.valueOf(nameObj).isBlank()) {
                throw new IllegalStateException("MCP tool '" + doc.getName() + "': inline lease config needs a 'name'");
            }
            builder.inline(LeaseDefinition.fromLayer(String.valueOf(nameObj), map));
        } else {
            throw new IllegalStateException("MCP tool '" + doc.getName() + "': 'lease' must be a name or a mapping");
        }

        log.info(
                "MCP tool '{}' uses lease '{}' (podBound={})",
                doc.getName(),
                param instanceof String s ? s : "inline",
                cfg.transport() == McpConfig.Transport.STDIO);
        return new McpLeaseGuard(builder.build());
    }

    private static Map<String, Object> initialPayload(McpConfig cfg, String connectionKey) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("connectionKey", connectionKey);
        if (cfg.url() != null) {
            payload.put("endpoint", cfg.url());
        } else if (cfg.command() != null && !cfg.command().isEmpty()) {
            payload.put("command", String.join(" ", cfg.command()));
        }
        return payload;
    }

    /** Lease-backed session guard — one per connection, single-threaded through the init lock. */
    private final class McpLeaseGuard implements SessionGuard {

        private final LeaseRequest request;
        private @Nullable Lease lease;

        private McpLeaseGuard(LeaseRequest request) {
            this.request = request;
        }

        @Override
        public synchronized void onSessionOpen() {
            lease = leaseService.acquire(request);
        }

        @Override
        @SuppressWarnings("PMD.CloseResource") // local view of the field; the guard releases in onSessionClose
        public synchronized void onSessionId(@Nullable String sessionId) {
            Lease held = lease;
            if (held == null || sessionId == null) return;
            Map<String, Object> payload = new HashMap<>(request.payload());
            payload.put("sessionId", sessionId);
            held.updatePayload(payload);
        }

        @Override
        @SuppressWarnings("PMD.CloseResource") // local view of the field; the guard releases in onSessionClose
        public synchronized void onSessionUse() {
            Lease held = lease;
            if (held != null) held.check();
        }

        @Override
        public synchronized void onSessionClose() {
            if (lease != null) {
                lease.release();
                lease = null;
            }
        }
    }
}
