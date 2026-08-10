package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON-RPC method dispatch for the MCP server surface. Pure protocol
 * logic — no HTTP awareness (that lives in {@link McpServerController}),
 * so it is unit-testable without a servlet context.
 *
 * <p>Exposes the full project-scoped tool catalogue from
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
 * <p>Only the three methods an MCP client needs for tool use are
 * implemented: {@code initialize}, {@code tools/list}, {@code tools/call}
 * (plus {@code ping}). Notifications (e.g. {@code notifications/initialized})
 * are accepted and ack'd with no body.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpServerService {

    /** MCP revision we speak — mirrors the client side in {@code McpConnection}. */
    static final String PROTOCOL_VERSION = "2025-03-26";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    private final ServerToolService serverToolService;
    private final ToolDispatcher toolDispatcher;
    private final ObjectMapper objectMapper;

    /**
     * Handle one JSON-RPC frame. Returns {@code Outcome.ack()} for
     * notifications (HTTP 202, empty body) and a fully-formed JSON-RPC
     * response envelope otherwise.
     */
    public Outcome handle(
            String body, String tenant, String project, @Nullable String username) {
        McpJsonRpc.Frame frame;
        try {
            frame = McpJsonRpc.parse(body);
        } catch (RuntimeException ex) {
            log.debug("MCP parse error: {}", ex.toString());
            return Outcome.body(errorEnvelope(null, PARSE_ERROR, "Parse error: " + ex.getMessage()));
        }

        if (frame instanceof McpJsonRpc.Frame.Notification n) {
            log.trace("MCP notification '{}' accepted", n.method());
            return Outcome.ack();
        }
        if (frame instanceof McpJsonRpc.Frame.Response) {
            // Clients don't send responses to us; ignore politely.
            return Outcome.ack();
        }

        McpJsonRpc.Frame.Request req = (McpJsonRpc.Frame.Request) frame;
        try {
            Object result = dispatch(req, tenant, project, username);
            return Outcome.body(resultEnvelope(req.id(), result));
        } catch (RpcError e) {
            return Outcome.body(errorEnvelope(req.id(), e.code, e.getMessage()));
        }
    }

    private Object dispatch(
            McpJsonRpc.Frame.Request req, String tenant, String project,
            @Nullable String username) {
        return switch (req.method()) {
            case "initialize" -> initialize(req.params());
            case "ping" -> Map.of();
            case "tools/list" -> toolsList(tenant, project, username);
            case "tools/call" -> toolsCall(req.params(), tenant, project, username);
            default -> throw new RpcError(
                    METHOD_NOT_FOUND, "Method not found: " + req.method());
        };
    }

    // ──────────────────── methods ────────────────────

    private Map<String, Object> initialize(@Nullable Map<String, Object> params) {
        String requested = params != null && params.get("protocolVersion") instanceof String s
                ? s : PROTOCOL_VERSION;
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", requested);
        out.put("capabilities", capabilities);
        out.put("serverInfo", Map.of("name", "vance-brain", "version", "1.0.0"));
        return out;
    }

    private Map<String, Object> toolsList(
            String tenant, String project, @Nullable String username) {
        ToolInvocationContext ctx =
                new ToolInvocationContext(tenant, project, null, null, username);
        List<Tool> tools = serverToolService.listAll(tenant, project, ctx);
        List<Map<String, Object>> arr = new ArrayList<>(tools.size());
        for (Tool t : tools) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("inputSchema", normalizeSchema(t.paramsSchema()));
            arr.add(m);
        }
        return Map.of("tools", arr);
    }

    private Map<String, Object> toolsCall(
            @Nullable Map<String, Object> params, String tenant, String project,
            @Nullable String username) {
        if (params == null || !(params.get("name") instanceof String name) || name.isBlank()) {
            throw new RpcError(INVALID_PARAMS, "tools/call requires a non-blank 'name'");
        }
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> raw
                ? castMap(raw) : new LinkedHashMap<>();
        ToolInvocationContext ctx =
                new ToolInvocationContext(tenant, project, null, null, username);
        try {
            Map<String, Object> result = toolDispatcher.invoke(name, arguments, ctx);
            return callContent(json(result), false);
        } catch (ToolException e) {
            // MCP convention: a tool that ran but failed is a *successful*
            // JSON-RPC response carrying isError=true, so the calling agent
            // sees the error text and can react instead of getting a
            // protocol-level failure.
            // The failure first, the troubleshooting hint behind it — the
            // hint used to be prepended to the message and read as advice
            // rather than as "the call did not happen".
            String msg = e.getMessage() == null ? "Tool failed" : e.getMessage();
            log.debug("MCP tools/call '{}' failed: {}", name, msg);
            String hint = e.getHint();
            return callContent(hint == null ? msg : msg + " -- hint: " + hint, true);
        }
    }

    // ──────────────────── helpers ────────────────────

    /** Wraps text into the MCP {@code {content:[{type:text}], isError}} shape. */
    private static Map<String, Object> callContent(String text, boolean isError) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(block));
        out.put("isError", isError);
        return out;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            return String.valueOf(value);
        }
    }

    /**
     * MCP requires {@code inputSchema} to be an object schema. Vance tools
     * already return one, but empty/type-less schemas get the minimal
     * {@code {type:object, properties:{}}} shell so strict clients accept them.
     */
    private static Map<String, Object> normalizeSchema(@Nullable Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema != null) {
            out.putAll(schema);
        }
        out.putIfAbsent("type", "object");
        out.putIfAbsent("properties", new LinkedHashMap<>());
        return out;
    }

    private static Map<String, Object> resultEnvelope(long id, Object result) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("jsonrpc", McpJsonRpc.JSONRPC_VERSION);
        env.put("id", id);
        env.put("result", result);
        return env;
    }

    private static Map<String, Object> errorEnvelope(
            @Nullable Long id, int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("jsonrpc", McpJsonRpc.JSONRPC_VERSION);
        env.put("id", id); // null is valid per JSON-RPC for parse errors
        env.put("error", err);
        return env;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** Internal signal for a protocol-level JSON-RPC error response. */
    private static final class RpcError extends RuntimeException {
        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Result of handling one frame: either a JSON-RPC envelope to serialise
     * ({@code body}), or a no-content ack for a notification.
     */
    public record Outcome(@Nullable Map<String, Object> body, boolean noContent) {

        public static Outcome body(Map<String, Object> body) {
            return new Outcome(body, false);
        }

        public static Outcome ack() {
            return new Outcome(null, true);
        }
    }
}
