package de.mhus.vance.toolpack.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.toolpack.core.McpJsonRpc;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.core.PackJson;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link McpHttpTransport} against a JDK in-process
 * HTTP server. Covers the two MCP-2025 response shapes:
 *
 * <ul>
 *   <li>{@code application/json} — single synchronous reply.</li>
 *   <li>{@code text/event-stream} — server upgrades to SSE; the
 *       transport must read multiple SSE frames until the matching
 *       response arrives, dispatching notifications along the way.</li>
 * </ul>
 */
class McpHttpTransportTest {

    private HttpServer server;
    private int port;
    private AtomicReference<String> lastRequestBody;
    private final McpJsonRpc rpc = new McpJsonRpc();

    @BeforeEach
    void start() throws IOException {
        lastRequestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void jsonResponse_returnsResultMap() {
        server.createContext("/mcp", this::respondJson);

        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://localhost:" + port + "/mcp"));
        try (McpHttpTransport t = new McpHttpTransport(cfg, rpc, new PackHttpClient(), SecretResolver.NOOP)) {
            t.open();
            Object result = t.sendRequest("tools/list", Map.of(),
                    Duration.ofSeconds(5), CTX);
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) result;
            assertThat(m).containsKey("tools");
        }
        assertThat(lastRequestBody.get()).contains("\"method\":\"tools/list\"");
    }

    @Test
    void sseResponse_streamsResultViaEventStream() {
        // SSE mode: server sends a sequence of "data: <json>\n\n" frames
        // including a notification then the response. Transport must
        // route the notification to the handler and surface the response.
        server.createContext("/mcp", this::respondSseWithNotification);

        AtomicReference<McpJsonRpc.Frame.Notification> capturedNotification = new AtomicReference<>();
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://localhost:" + port + "/mcp"));
        try (McpHttpTransport t = new McpHttpTransport(cfg, rpc, new PackHttpClient(), SecretResolver.NOOP)) {
            t.open();
            t.setNotificationHandler(capturedNotification::set);
            Object result = t.sendRequest("tools/list", Map.of(),
                    Duration.ofSeconds(5), CTX);
            assertThat(result).isInstanceOf(Map.class);
        }
        assertThat(capturedNotification.get()).isNotNull();
        assertThat(capturedNotification.get().method())
                .isEqualTo("notifications/tools/list_changed");
    }

    @Test
    void bearerAuth_secretIsResolvedAtSendTime() {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        server.createContext("/mcp", ex -> {
            capturedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            respondJson(ex);
        });

        SecretResolver resolver = (input, ctx) ->
                input == null ? null : input.replace("{{secret:tok}}", "supersecret");
        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://localhost:" + port + "/mcp",
                "auth", Map.of("type", "bearer", "token", "{{secret:tok}}")));
        try (McpHttpTransport t = new McpHttpTransport(cfg, rpc, new PackHttpClient(), resolver)) {
            t.open();
            t.sendRequest("tools/list", Map.of(), Duration.ofSeconds(5), CTX);
        }
        assertThat(capturedAuth.get()).isEqualTo("Bearer supersecret");
    }

    @Test
    void errorResponse_mapsToJsonRpcException() {
        server.createContext("/mcp", this::respondJsonError);

        McpConfig cfg = McpConfig.fromParameters(Map.of(
                "transport", "http",
                "url", "http://localhost:" + port + "/mcp"));
        try (McpHttpTransport t = new McpHttpTransport(cfg, rpc, new PackHttpClient(), SecretResolver.NOOP)) {
            t.open();
            try {
                t.sendRequest("tools/list", Map.of(), Duration.ofSeconds(5), CTX);
                throw new AssertionError("expected JsonRpcException");
            } catch (McpJsonRpc.JsonRpcException expected) {
                assertThat(expected.code()).isEqualTo(-32601);
                assertThat(expected.getMessage()).contains("Method not found");
            }
        }
    }

    // ─────── Test handlers ───────

    private void respondJson(HttpExchange ex) throws IOException {
        long requestId = parseRequestId(ex);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", requestId);
        envelope.put("result", Map.of("tools", List.of()));
        byte[] resp = PackJson.write(envelope).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, resp.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
    }

    private void respondSseWithNotification(HttpExchange ex) throws IOException {
        long requestId = parseRequestId(ex);
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            // First a notification frame.
            String notif = PackJson.write(Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/tools/list_changed"));
            os.write(("data: " + notif + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            // Then the response frame.
            String resp = PackJson.write(Map.of(
                    "jsonrpc", "2.0",
                    "id", requestId,
                    "result", Map.of("tools", List.of())));
            os.write(("data: " + resp + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private void respondJsonError(HttpExchange ex) throws IOException {
        long requestId = parseRequestId(ex);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", requestId);
        envelope.put("error", Map.of("code", -32601, "message", "Method not found"));
        byte[] resp = PackJson.write(envelope).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, resp.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
    }

    @SuppressWarnings("unchecked")
    private long parseRequestId(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastRequestBody.set(body);
        Object parsed = PackJson.read(body);
        if (parsed instanceof Map<?, ?> m) {
            Object id = ((Map<String, Object>) m).get("id");
            if (id instanceof Number n) return n.longValue();
        }
        return 1L;
    }

    private static final ToolInvocationContext CTX = new ToolInvocationContext(
            "tenant", "project", "session", "process", "user");
}
