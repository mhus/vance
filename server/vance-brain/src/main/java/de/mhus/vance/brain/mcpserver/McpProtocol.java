package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * JSON-RPC 2.0 shell shared by the two MCP server surfaces: the global
 * tool-catalogue endpoint ({@code POST /brain/{tenant}/mcp}) and the
 * project-scoped, config-regulated endpoint
 * ({@code POST /brain/{tenant}/mcp/{project}}). Frame parsing, the
 * notification/request discrimination, envelope building and the MCP
 * result shapes ({@code initialize}, {@code tools/list},
 * {@code tools/call}) live here exactly once — the two services differ
 * only in how they resolve the catalogue and execute a call.
 *
 * <p>Protocol facts both surfaces must answer identically:
 * <ul>
 *   <li>MCP revision {@value #PROTOCOL_VERSION} — mirrors the client
 *       side in {@code McpConnection}. {@code initialize} echoes a
 *       client-requested version back; the envelope is
 *       version-agnostic.</li>
 *   <li>Notifications (e.g. {@code notifications/initialized}) are
 *       ack'd with no body (HTTP 202); response frames a client might
 *       send are ignored politely.</li>
 *   <li>A tool that <b>ran</b> and failed is a <em>successful</em>
 *       JSON-RPC response carrying {@code isError:true} — MCP
 *       convention, so the calling agent sees the error text and can
 *       react instead of getting a protocol-level failure.</li>
 *   <li>Protocol errors use the canonical codes: {@code -32700} parse,
 *       {@code -32601} unknown method, {@code -32602} bad
 *       {@code tools/call} params.</li>
 * </ul>
 */
@Slf4j
final class McpProtocol {

    /** MCP revision we speak — mirrors the client side in {@code McpConnection}. */
    static final String PROTOCOL_VERSION = "2025-03-26";

    /** {@code serverInfo} block — one server, two doors. */
    static final String SERVER_NAME = "vance-brain";

    static final String SERVER_VERSION = "1.0.0";

    static final int PARSE_ERROR = -32700;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private McpProtocol() {}

    /** The surface-specific half of the protocol: resolves one request method. */
    @FunctionalInterface
    interface Dispatcher {

        Object dispatch(McpJsonRpc.Frame.Request request);
    }

    /** Internal signal for a protocol-level JSON-RPC error response. */
    static final class RpcError extends RuntimeException {

        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Result of handling one frame: either a JSON-RPC envelope to
     * serialise ({@code body}), or a no-content ack for a notification.
     */
    record Outcome(@Nullable Map<String, Object> body, boolean noContent) {

        static Outcome body(Map<String, Object> body) {
            return new Outcome(body, false);
        }

        static Outcome ack() {
            return new Outcome(null, true);
        }
    }

    /**
     * Handle one JSON-RPC frame against the given surface. Parse errors,
     * notifications and envelope building are protocol business and stay
     * here; everything inside the request is the surface's
     * ({@link Dispatcher}).
     */
    static Outcome handle(String body, Dispatcher dispatcher) {
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
            Object result = dispatcher.dispatch(req);
            return Outcome.body(resultEnvelope(req.id(), result));
        } catch (RpcError e) {
            return Outcome.body(errorEnvelope(req.id(), e.code, e.getMessage()));
        }
    }

    // ──────────────────── shared method results ────────────────────

    /** {@code initialize} — echoes the requested protocol version if present. */
    static Map<String, Object> initialize(@Nullable Map<String, Object> params) {
        String requested = params != null && params.get("protocolVersion") instanceof String s ? s : PROTOCOL_VERSION;
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", requested);
        out.put("capabilities", capabilities);
        out.put("serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION));
        return out;
    }

    /** Maps a tool catalogue to the MCP {@code {tools:[…]}} listing shape. */
    static Map<String, Object> toolsListResult(List<Tool> tools) {
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

    /** A parsed {@code tools/call} request. */
    record ToolCall(String name, Map<String, Object> arguments) {}

    /** Extracts {@code name}/{@code arguments} from {@code tools/call} params. */
    static ToolCall parseToolCall(@Nullable Map<String, Object> params) {
        if (params == null || !(params.get("name") instanceof String name) || name.isBlank()) {
            throw new RpcError(INVALID_PARAMS, "tools/call requires a non-blank 'name'");
        }
        Map<String, Object> arguments =
                params.get("arguments") instanceof Map<?, ?> raw ? castMap(raw) : new LinkedHashMap<>();
        return new ToolCall(name, arguments);
    }

    /** Wraps text into the MCP {@code {content:[{type:text}], isError}} shape. */
    static Map<String, Object> callContent(String text, boolean isError) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(block));
        out.put("isError", isError);
        return out;
    }

    // ──────────────────── envelopes & helpers ────────────────────

    /**
     * MCP requires {@code inputSchema} to be an object schema. Vance tools
     * already return one, but empty/type-less schemas get the minimal
     * {@code {type:object, properties:{}}} shell so strict clients accept
     * them.
     */
    static Map<String, Object> normalizeSchema(@Nullable Map<String, Object> schema) {
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

    private static Map<String, Object> errorEnvelope(@Nullable Long id, int code, String message) {
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
}
