package de.mhus.vance.toolpack.mcp;

import de.mhus.vance.toolpack.core.McpJsonRpc;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.rest.AuthSpec;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * MCP transport over HTTP. Two flavours, distinguished by config:
 *
 * <ul>
 *   <li><b>Streamable HTTP</b> (current MCP 2025-spec) — one
 *       {@code parameters.url}. Each request is a POST whose response
 *       is either {@code application/json} (synchronous reply) or
 *       {@code text/event-stream} (server upgrades to SSE for the
 *       reply + subsequent notifications). Auto-detected via the
 *       response Content-Type header.</li>
 *   <li><b>Legacy HTTP+SSE</b> (older 2024-era servers) —
 *       {@code parameters.postUrl} for client requests,
 *       {@code parameters.sseUrl} for the server-push channel. A
 *       background thread keeps the SSE channel open; responses are
 *       correlated via a shared id across the two endpoints.</li>
 * </ul>
 *
 * <p>v1 implements the Streamable HTTP path fully. The legacy split-
 * endpoint mode is detected and rejected with a clear error until
 * the legacy SSE pump is wired (deferred — most modern servers
 * already speak Streamable HTTP).
 */
@Slf4j
public final class McpHttpTransport implements McpTransport {

    private final McpConfig config;
    private final McpJsonRpc rpc;
    private final PackHttpClient httpClient;
    private final SecretResolver secretResolver;

    private final AtomicReference<Consumer<McpJsonRpc.Frame.Notification>> notificationHandler =
            new AtomicReference<>();
    private final ConcurrentLinkedQueue<McpJsonRpc.Frame.Notification> pendingNotifications =
            new ConcurrentLinkedQueue<>();
    /**
     * Streamable-HTTP session id assigned by the server. Per MCP spec the
     * server may return an {@code Mcp-Session-Id} header on the
     * {@code initialize} response; we capture it here and echo it back on
     * every subsequent request. Servers that don't use sessions never set
     * it and the field stays {@code null}. JetBrains' built-in MCP server
     * is one of the session-based ones — without this round-trip,
     * {@code tools/list} returns 0 tools.
     */
    private final AtomicReference<@Nullable String> sessionId = new AtomicReference<>();
    private volatile boolean open;

    /** Header name from the MCP Streamable-HTTP transport spec. Case-insensitive on the wire. */
    static final String SESSION_HEADER = "Mcp-Session-Id";

    public McpHttpTransport(
            McpConfig config,
            McpJsonRpc rpc,
            PackHttpClient httpClient,
            SecretResolver secretResolver) {
        this.config = config;
        this.rpc = rpc;
        this.httpClient = httpClient;
        this.secretResolver = secretResolver == null ? SecretResolver.NOOP : secretResolver;
    }

    @Override
    public synchronized void open() {
        if (open) return;
        if (config.transport() != McpConfig.Transport.HTTP) {
            throw new IllegalStateException(
                    "McpHttpTransport: config.transport must be HTTP, got " + config.transport());
        }
        if (config.url() == null) {
            throw new IllegalStateException(
                    "McpHttpTransport: legacy HTTP+SSE (postUrl/sseUrl split) is not "
                            + "implemented yet — use Streamable HTTP (single 'url')");
        }
        open = true;
        log.info("McpHttpTransport opened: url={}", config.url());
    }

    @Override
    public synchronized void close() {
        if (!open) return;
        open = false;
        sessionId.set(null);
        log.info("McpHttpTransport closed");
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public @Nullable Object sendRequest(
            String method,
            @Nullable Map<String, Object> params,
            Duration timeout,
            ToolInvocationContext ctx) {
        if (!open) throw new IllegalStateException("MCP HTTP transport not open");
        long id = rpc.allocId();
        String body = rpc.buildRequest(id, method, params);
        HttpResponse<InputStream> response = post(body, timeout, ctx);
        return parseResponse(response, id, method);
    }

    @Override
    public void sendNotification(
            String method, @Nullable Map<String, Object> params, ToolInvocationContext ctx) {
        if (!open) throw new IllegalStateException("MCP HTTP transport not open");
        String body = rpc.buildNotification(method, params);
        // Notifications still POST; server returns 202 / 200 with no body.
        HttpResponse<InputStream> response = post(body,
                Duration.ofSeconds(Math.min(10, config.timeoutSeconds())), ctx);
        // Drain whatever came back so the connection releases.
        try { response.body().close(); } catch (IOException ignored) { }
    }

    @Override
    public void setNotificationHandler(@Nullable Consumer<McpJsonRpc.Frame.Notification> handler) {
        notificationHandler.set(handler);
        if (handler != null) {
            // Drain anything that arrived during the gap — Streamable-HTTP
            // can deliver notifications inside the same SSE response that
            // carried a request reply, so the queue may already be primed.
            McpJsonRpc.Frame.Notification n;
            while ((n = pendingNotifications.poll()) != null) {
                try { handler.accept(n); }
                catch (RuntimeException e) {
                    log.warn("MCP HTTP notification handler threw: {}", e.toString());
                }
            }
        }
    }

    // ─────── Internals ───────

    private HttpResponse<InputStream> post(
            String body, Duration timeout, ToolInvocationContext ctx) {
        if (log.isTraceEnabled()) {
            // Permanent dev-trace of outgoing JSON-RPC bodies. Keep at
            // TRACE — debug is too chatty for routine runs but this is
            // exactly the line you want when an MCP server returns a
            // generic "trouble completing this action" and you need to
            // see what arguments actually went over the wire.
            log.trace("MCP HTTP POST {} body={}", config.url(),
                    body.length() > 800 ? body.substring(0, 800) + "…" : body);
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(config.url()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                // Per MCP Streamable-HTTP spec: Accept lists both response
                // types so the server picks (sync JSON vs. SSE upgrade).
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        applyAuthHeader(rb, ctx);
        String sid = sessionId.get();
        if (sid != null) {
            rb.header(SESSION_HEADER, sid);
        }

        HttpClient client = httpClient.client(config.tls());
        try {
            HttpResponse<InputStream> response = client.send(
                    rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            captureSessionId(response);
            return response;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "MCP HTTP POST failed (" + config.url() + "): " + e.getMessage(), e);
        }
    }

    /**
     * Captures the {@code Mcp-Session-Id} header from a response if the
     * server set one. First-write-wins: servers that re-issue a session
     * id on every response would only have the very first one stick,
     * which matches the spec's "client must continue to use the
     * received Mcp-Session-Id" until the server signals otherwise.
     * Header lookup is case-insensitive via {@link java.net.http.HttpHeaders}.
     */
    private void captureSessionId(HttpResponse<?> response) {
        response.headers().firstValue(SESSION_HEADER)
                .filter(s -> !s.isBlank())
                .ifPresent(value -> sessionId.compareAndSet(null, value));
    }

    private void applyAuthHeader(HttpRequest.Builder rb, ToolInvocationContext ctx) {
        AuthSpec auth = config.auth();
        switch (auth.type()) {
            case NONE -> { /* no auth */ }
            case BEARER -> {
                String token = secretResolver.resolve(auth.token(), ctx);
                if (token != null) rb.header("Authorization", PackHttpClient.bearerAuthHeader(token));
            }
            case BASIC -> {
                String user = secretResolver.resolve(auth.user(), ctx);
                String pwd = secretResolver.resolve(auth.password(), ctx);
                rb.header("Authorization",
                        PackHttpClient.basicAuthHeader(user == null ? "" : user, pwd == null ? "" : pwd));
            }
            case API_KEY -> {
                if (auth.headerName() != null && !auth.headerName().isBlank()) {
                    String value = secretResolver.resolve(auth.value(), ctx);
                    if (value != null) rb.header(auth.headerName(), value);
                }
                // queryParamName variant — MCP-HTTP doesn't use query-string
                // auth, so we ignore it (config validation could warn).
            }
        }
    }

    private @Nullable Object parseResponse(
            HttpResponse<InputStream> response, long requestId, String requestMethod) {
        String contentType = response.headers().firstValue("Content-Type")
                .or(() -> response.headers().firstValue("content-type"))
                .orElse("application/json");
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = readAllText(response.body());
            throw new IllegalStateException(
                    "MCP HTTP " + status + " from " + config.url() + ": " + truncate(body));
        }
        if (contentType.toLowerCase().contains("text/event-stream")) {
            return parseSseStream(response.body(), requestId, requestMethod);
        }
        // Single JSON frame.
        String body = readAllText(response.body());
        if (log.isTraceEnabled()) {
            log.trace("MCP HTTP {} response body={}",
                    requestMethod,
                    body.length() > 800 ? body.substring(0, 800) + "…" : body);
        }
        if (body.isBlank()) return null;
        McpJsonRpc.Frame frame = McpJsonRpc.parse(body);
        if (frame instanceof McpJsonRpc.Frame.Response r) {
            if (r.id() != requestId) {
                throw new IllegalStateException(
                        "MCP HTTP response id-mismatch: expected=" + requestId + " got=" + r.id());
            }
            if (r.error() != null) throw McpJsonRpc.JsonRpcException.fromMap(r.error());
            return r.result();
        }
        throw new IllegalStateException(
                "MCP HTTP unexpected frame for request method='" + requestMethod
                        + "': " + truncate(body));
    }

    /**
     * Reads the SSE stream until the matching response arrives. SSE
     * frames look like {@code data: <line>\n\n}; multi-line {@code data:}
     * blocks concatenate with newlines per spec. We keep reading
     * notifications and routing them to the handler until the response
     * with {@code id == requestId} arrives, then drop the rest of the
     * stream (server may keep it open for more notifications, but for
     * synchronous request/response that's discarded — connection-layer
     * SSE keep-open is a future feature).
     */
    private @Nullable Object parseSseStream(
            InputStream stream, long requestId, String requestMethod) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder dataBuf = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    // Frame boundary — process accumulated data.
                    if (dataBuf.length() > 0) {
                        String frame = dataBuf.toString();
                        if (log.isTraceEnabled()) {
                            log.trace("MCP HTTP {} SSE frame body={}",
                                    requestMethod,
                                    frame.length() > 800 ? frame.substring(0, 800) + "…" : frame);
                        }
                        Object result = handleSseFrame(frame, requestId, requestMethod);
                        dataBuf.setLength(0);
                        if (result != SSE_NOT_RESPONSE) return result;
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (dataBuf.length() > 0) dataBuf.append('\n');
                    dataBuf.append(line.substring(5).stripLeading());
                }
                // Ignore other SSE fields (event:, id:, retry:) — MCP-HTTP
                // only carries data.
            }
            // Stream ended without a matching response.
            throw new IllegalStateException(
                    "MCP HTTP SSE stream closed before response for method='"
                            + requestMethod + "' id=" + requestId);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "MCP HTTP SSE read failed: " + e.getMessage(), e);
        }
    }

    /**
     * Sentinel that {@link #parseSseStream} keeps reading after a
     * non-matching frame. Distinct from {@code null} (a legitimate
     * "result is null" response).
     */
    private static final Object SSE_NOT_RESPONSE = new Object();

    private Object handleSseFrame(String json, long requestId, String requestMethod) {
        McpJsonRpc.Frame frame;
        try {
            frame = McpJsonRpc.parse(json);
        } catch (RuntimeException e) {
            log.debug("MCP HTTP SSE: dropping malformed frame: {}", truncate(json));
            return SSE_NOT_RESPONSE;
        }
        if (frame instanceof McpJsonRpc.Frame.Response r && r.id() == requestId) {
            if (r.error() != null) throw McpJsonRpc.JsonRpcException.fromMap(r.error());
            return r.result();
        }
        if (frame instanceof McpJsonRpc.Frame.Notification n) {
            Consumer<McpJsonRpc.Frame.Notification> h = notificationHandler.get();
            if (h != null) {
                try { h.accept(n); }
                catch (RuntimeException e) {
                    log.warn("MCP HTTP SSE notification handler threw: {}", e.toString());
                }
            } else {
                pendingNotifications.add(n);
            }
        }
        return SSE_NOT_RESPONSE;
    }

    private static String readAllText(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read MCP HTTP response: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 197) + "...";
    }

}
