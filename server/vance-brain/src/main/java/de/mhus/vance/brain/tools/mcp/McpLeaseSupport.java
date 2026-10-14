package de.mhus.vance.brain.tools.mcp;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.Lease;
import de.mhus.vance.shared.lease.LeaseDefinition;
import de.mhus.vance.shared.lease.LeaseLostException;
import de.mhus.vance.shared.lease.LeaseRequest;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.mcp.McpConfig;
import de.mhus.vance.toolpack.mcp.McpSessionRevokedException;
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

    /** Payload keys the {@link McpLeaseHandler} reads back. */
    static final String PAYLOAD_CONNECTION_KEY = "connectionKey";

    static final String PAYLOAD_TOOL = "tool";

    static final String PAYLOAD_SESSION_ID = "sessionId";

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
                .payload(initialPayload(doc, cfg, connectionKey));

        if (param instanceof String name) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) return null;
            builder.lease(trimmed);
        } else if (param instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) raw;
            builder.inline(inlineDefinition(doc, map, ctx));
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

    /**
     * An inline definition is written by whoever may edit the tool config
     * — typically a project. It therefore carries only the fields the
     * project layer owns ({@code levels.project}, {@code levels.user},
     * {@code ttlSeconds}, {@code waitMs}) and counts on a resource private
     * to its tenant/project: an inline {@code resource: tex2pdf} with a
     * high {@code levels.global} would otherwise book the shared funnel of
     * every tenant. Shared caps belong in a named lease config.
     */
    private static LeaseDefinition inlineDefinition(
            ServerToolDocument doc, Map<String, Object> map, ToolInvocationContext ctx) {
        Object nameObj = map.get("name");
        String name = nameObj == null ? "" : String.valueOf(nameObj).trim();
        if (!LeaseDefinition.isValidName(name)) {
            throw new IllegalStateException("MCP tool '" + doc.getName()
                    + "': inline lease config needs a valid 'name' (" + LeaseDefinition.NAME_PATTERN.pattern() + ")");
        }
        LeaseDefinition parsed;
        try {
            parsed = LeaseDefinition.fromLayer(name, map);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "MCP tool '" + doc.getName() + "': invalid inline lease config: " + e.getMessage(), e);
        }
        if (parsed.resource() != null || parsed.maxGlobal() != null || parsed.maxTenant() != null) {
            throw new IllegalStateException("MCP tool '" + doc.getName()
                    + "': an inline lease config may only set levels.project, levels.user, ttlSeconds and waitMs"
                    + " — reference a named lease (_vance/config/lease/<name>.yaml) for shared caps");
        }
        String scope =
                Objects.requireNonNullElse(ctx.tenantId(), "") + "/" + Objects.requireNonNullElse(ctx.projectId(), "");
        return new LeaseDefinition(
                name,
                "mcp-inline:" + scope + "/" + name,
                null,
                null,
                parsed.maxProject(),
                parsed.maxUser(),
                parsed.ttlSeconds(),
                parsed.waitMs());
    }

    private static Map<String, Object> initialPayload(ServerToolDocument doc, McpConfig cfg, String connectionKey) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(PAYLOAD_CONNECTION_KEY, connectionKey);
        // Tool name: the reaper on a foreign pod reloads the config (URL,
        // auth) through the cascade to DELETE the remote session.
        payload.put(PAYLOAD_TOOL, doc.getName());
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
            payload.put(PAYLOAD_SESSION_ID, sessionId);
            held.updatePayload(payload);
        }

        @Override
        @SuppressWarnings("PMD.CloseResource") // local view of the field; the guard releases in onSessionClose
        public synchronized void onSessionUse() {
            Lease held = lease;
            if (held == null) return;
            try {
                held.check();
            } catch (LeaseLostException e) {
                throw new McpSessionRevokedException(e.getMessage(), e);
            }
        }

        @Override
        public synchronized @Nullable String sessionToken() {
            Lease held = lease;
            return held == null || held.isNoop() ? null : held.holderToken();
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
