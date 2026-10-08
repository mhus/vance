package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON-RPC method dispatch for the <b>global</b> MCP server surface
 * ({@code POST /brain/{tenant}/mcp}) — the full, project-scoped tool
 * catalogue. Pure protocol logic sits in {@link McpProtocol}; this class
 * is the surface: it answers {@code tools/list} from
 * {@link ServerToolService#listAll} and routes {@code tools/call} through
 * {@link ToolDispatcher#invoke}, which enforces {@code Action.EXECUTE}
 * per invocation against the calling identity. There is deliberately
 * <b>no</b> tool allow-list here: on a closed test system every tool is
 * exposed, and any real restriction belongs in the permission grants of
 * the calling (service) account — a filter stored where the agent could
 * rewrite it would be self-defeating. A future server-config allow-list
 * (never a tenant document) would slot into {@link #toolsList} and
 * {@link #toolsCall}.
 *
 * <p>The narrow, config-regulated counter-surface for external agents is
 * {@link McpProjectService} — different trust model, different endpoint,
 * same protocol shell.
 *
 * <p>Only the methods an MCP client needs for tool use are implemented:
 * {@code initialize}, {@code tools/list}, {@code tools/call} (plus
 * {@code ping}). Notifications are accepted and ack'd with no body.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpServerService {

    /** @see McpProtocol#PROTOCOL_VERSION */
    static final String PROTOCOL_VERSION = McpProtocol.PROTOCOL_VERSION;

    private final ServerToolService serverToolService;
    private final ToolDispatcher toolDispatcher;
    private final ObjectMapper objectMapper;

    /**
     * Handle one JSON-RPC frame. Returns {@code Outcome.ack()} for
     * notifications (HTTP 202, empty body) and a fully-formed JSON-RPC
     * response envelope otherwise.
     */
    public McpProtocol.Outcome handle(String body, String tenant, String project, @Nullable String username) {
        return McpProtocol.handle(body, req -> dispatch(req, tenant, project, username));
    }

    private Object dispatch(McpJsonRpc.Frame.Request req, String tenant, String project, @Nullable String username) {
        return switch (req.method()) {
            case "initialize" -> McpProtocol.initialize(req.params());
            case "ping" -> Map.of();
            case "tools/list" -> toolsList(tenant, project, username);
            case "tools/call" -> toolsCall(req.params(), tenant, project, username);
            default ->
                throw new McpProtocol.RpcError(McpProtocol.METHOD_NOT_FOUND, "Method not found: " + req.method());
        };
    }

    // ──────────────────── methods ────────────────────

    private Map<String, Object> toolsList(String tenant, String project, @Nullable String username) {
        ToolInvocationContext ctx = new ToolInvocationContext(tenant, project, null, null, username);
        return McpProtocol.toolsListResult(serverToolService.listAll(tenant, project, ctx));
    }

    private Map<String, Object> toolsCall(
            @Nullable Map<String, Object> params, String tenant, String project, @Nullable String username) {
        McpProtocol.ToolCall call = McpProtocol.parseToolCall(params);
        ToolInvocationContext ctx = new ToolInvocationContext(tenant, project, null, null, username);
        try {
            Map<String, Object> result = toolDispatcher.invoke(call.name(), call.arguments(), ctx);
            return McpProtocol.callContent(json(result), false);
        } catch (ToolException e) {
            // MCP convention: a tool that ran but failed is a *successful*
            // JSON-RPC response carrying isError=true, so the calling agent
            // sees the error text and can react instead of getting a
            // protocol-level failure.
            // The failure first, the troubleshooting hint behind it — the
            // hint used to be prepended to the message and read as advice
            // rather than as "the call did not happen".
            String msg = e.getMessage() == null ? "Tool failed" : e.getMessage();
            log.debug("MCP tools/call '{}' failed: {}", call.name(), msg);
            String hint = e.getHint();
            return McpProtocol.callContent(hint == null ? msg : msg + " -- hint: " + hint, true);
        }
    }

    // ──────────────────── helpers ────────────────────

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }
}
