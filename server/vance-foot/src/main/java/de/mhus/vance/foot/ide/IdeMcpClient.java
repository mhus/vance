package de.mhus.vance.foot.ide;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Single-connection JSON-RPC 2.0 client for the Claude Code IDE plugin's
 * MCP-over-WebSocket endpoint. Owns one {@link WebSocket}, performs the
 * MCP handshake (planning §2), correlates request/response by integer id,
 * answers server-side {@code ping}s and forwards notifications to a
 * {@link Consumer}.
 *
 * <p>Lifecycle is fail-fast: {@link #connect()} either returns an open
 * client or throws. There is no auto-reconnect inside this class — that
 * sits one level up in {@link IdeBridgeService}, which throws away a dead
 * client and builds a new one.
 *
 * <p>Threading: outgoing calls are issued from any thread and serialised
 * through {@link WebSocket#sendText}. Incoming frames arrive on the JDK
 * HTTP-Client reader thread; we parse them there and complete pending
 * futures or invoke the notification handler synchronously. Handlers are
 * expected to return promptly.
 */
@Slf4j
public final class IdeMcpClient {

    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final String MCP_SUBPROTOCOL = "mcp";
    private static final String AUTH_HEADER = "X-Claude-Code-Ide-Authorization";

    /** Tools the bridge currently uses; everything else from {@code tools/list} is ignored. */
    static final Set<String> KNOWN_TOOLS = Set.of(
            "get_all_opened_file_paths",
            "openFile",
            "open_files",
            "close_tab",
            "openDiff",
            "closeAllDiffTabs",
            "reformat_file",
            "getDiagnostics");

    private final URI uri;
    private final String authToken;
    private final long pid;
    private final String clientName;
    private final String clientVersion;
    private final Consumer<Notification> notificationSink;
    private final ObjectMapper json = JsonMapper.builder().build();

    private final AtomicLong nextId = new AtomicLong(0);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicReference<@Nullable WebSocket> wsRef = new AtomicReference<>();
    private final AtomicReference<Set<String>> toolsRef = new AtomicReference<>(Set.of());
    private final StringBuilder textBuffer = new StringBuilder();

    public IdeMcpClient(URI uri,
                        String authToken,
                        long pid,
                        String clientName,
                        String clientVersion,
                        Consumer<Notification> notificationSink) {
        this.uri = uri;
        this.authToken = authToken;
        this.pid = pid;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
        this.notificationSink = notificationSink;
    }

    /**
     * Opens the WebSocket and runs the MCP handshake (initialize →
     * initialized → ide_connected → tools/list). Throws on any failure.
     */
    public void connect() throws Exception {
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols(MCP_SUBPROTOCOL)
                .header(AUTH_HEADER, authToken)
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, new Listener())
                .get(10, TimeUnit.SECONDS);
        wsRef.set(socket);

        ObjectNode initParams = json.createObjectNode()
                .put("protocolVersion", MCP_PROTOCOL_VERSION);
        initParams.set("capabilities", json.createObjectNode()
                .set("roots", json.createObjectNode())
                .set("sampling", json.createObjectNode()));
        initParams.set("clientInfo", json.createObjectNode()
                .put("name", clientName)
                .put("version", clientVersion));
        request("initialize", initParams, Duration.ofSeconds(10));

        notify("notifications/initialized", null);
        notify("ide_connected", json.createObjectNode().put("pid", pid));

        try {
            JsonNode tools = request("tools/list", null, Duration.ofSeconds(5));
            Set<String> names = parseToolNames(tools);
            toolsRef.set(names);
            log.debug("IDE bridge advertises tools: {}", names);
        } catch (Exception e) {
            log.warn("tools/list failed — continuing without cached tool list: {}", e.toString());
        }
    }

    /** Set of tool names advertised by {@code tools/list}, populated during connect. */
    public Set<String> tools() {
        return toolsRef.get();
    }

    /** True when the underlying WebSocket is still open. */
    public boolean isOpen() {
        WebSocket ws = wsRef.get();
        return ws != null && !ws.isOutputClosed() && !ws.isInputClosed();
    }

    public void close(String reason) {
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null && !ws.isOutputClosed()) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, reason)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(t -> null);
        }
        failAllPending(new IllegalStateException("client closed: " + reason));
    }

    /**
     * Sends a JSON-RPC request and awaits the response. The id is generated
     * here. Errors from the server surface as {@link IdeRpcException}.
     */
    public JsonNode request(String method, @Nullable JsonNode params, Duration timeout)
            throws IdeRpcException, TimeoutException, InterruptedException {
        WebSocket ws = wsRef.get();
        if (ws == null) {
            throw new IllegalStateException("not connected");
        }
        long id = nextId.incrementAndGet();
        ObjectNode envelope = json.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method);
        if (params != null) {
            envelope.set("params", params);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            ws.sendText(json.writeValueAsString(envelope), true)
                    .get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            pending.remove(id);
            throw new IdeRpcException(-1, "send failed: " + cause(e), null);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw e;
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IdeRpcException rpc) {
                throw rpc;
            }
            throw new IdeRpcException(-1, "request failed: " + cause(e), null);
        }
    }

    /** Sends a JSON-RPC notification (no id, no response). */
    public void notify(String method, @Nullable JsonNode params) {
        WebSocket ws = wsRef.get();
        if (ws == null) {
            return;
        }
        ObjectNode envelope = json.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", method);
        if (params != null) {
            envelope.set("params", params);
        }
        try {
            ws.sendText(json.writeValueAsString(envelope), true)
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("notification {} failed: {}", method, e.toString());
        }
    }

    private void failAllPending(Throwable cause) {
        pending.forEach((id, future) -> future.completeExceptionally(cause));
        pending.clear();
    }

    private static Set<String> parseToolNames(JsonNode toolsResult) {
        JsonNode list = toolsResult.get("tools");
        if (list == null || !list.isArray()) {
            return Set.of();
        }
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (JsonNode entry : list) {
            JsonNode name = entry.get("name");
            if (name != null && name.isString()) {
                names.add(name.asString());
            }
        }
        return Set.copyOf(names);
    }

    private static String cause(Exception e) {
        Throwable c = e.getCause();
        return c == null ? e.toString() : c.toString();
    }

    private void onTextFrame(String frame) {
        JsonNode message;
        try {
            message = json.readTree(frame);
        } catch (Exception e) {
            log.warn("malformed JSON-RPC frame: {}", e.toString());
            return;
        }
        JsonNode idNode = message.get("id");
        String method = textOrNull(message, "method");
        if (method != null && idNode != null && !idNode.isNull()) {
            handleServerRequest(method, idNode, message.get("params"));
            return;
        }
        if (method != null) {
            handleNotification(method, message.get("params"));
            return;
        }
        if (idNode != null && !idNode.isNull()) {
            handleResponse(idNode.asLong(0), message);
        }
    }

    private void handleServerRequest(String method, JsonNode idNode, @Nullable JsonNode params) {
        WebSocket ws = wsRef.get();
        if (ws == null) {
            return;
        }
        if ("ping".equals(method)) {
            ObjectNode reply = json.createObjectNode()
                    .put("jsonrpc", "2.0");
            reply.set("id", idNode);
            reply.set("result", json.createObjectNode());
            try {
                ws.sendText(json.writeValueAsString(reply), true);
            } catch (Exception e) {
                log.warn("ping reply failed: {}", e.toString());
            }
            return;
        }
        ObjectNode err = json.createObjectNode().put("jsonrpc", "2.0");
        err.set("id", idNode);
        ObjectNode errBody = json.createObjectNode()
                .put("code", -32_601)
                .put("message", "Method not implemented: " + method);
        err.set("error", errBody);
        try {
            ws.sendText(json.writeValueAsString(err), true);
        } catch (Exception e) {
            log.warn("error reply failed: {}", e.toString());
        }
    }

    private void handleNotification(String method, @Nullable JsonNode params) {
        notificationSink.accept(new Notification(method, params));
    }

    private void handleResponse(long id, JsonNode message) {
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            log.debug("response for unknown id {}", id);
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            int code = error.has("code") ? error.get("code").asInt(-1) : -1;
            String msg = textOrNull(error, "message");
            future.completeExceptionally(new IdeRpcException(code,
                    msg == null ? "(no message)" : msg, error.get("data")));
            return;
        }
        future.complete(message.get("result"));
    }

    private static @Nullable String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** Server-pushed JSON-RPC notification, delivered to the sink. */
    public record Notification(String method, @Nullable JsonNode params) {
    }

    private final class Listener implements WebSocket.Listener {

        @Override
        public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String complete = textBuffer.toString();
                textBuffer.setLength(0);
                onTextFrame(complete);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket socket, int statusCode, String reason) {
            wsRef.compareAndSet(socket, null);
            failAllPending(new IllegalStateException(
                    "WebSocket closed: " + statusCode + (reason.isEmpty() ? "" : " (" + reason + ")")));
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.warn("WebSocket error: {}", error.toString());
            wsRef.compareAndSet(socket, null);
            failAllPending(error);
        }

        @Override
        public CompletableFuture<?> onPing(WebSocket socket, ByteBuffer message) {
            socket.request(1);
            return null;
        }
    }
}
