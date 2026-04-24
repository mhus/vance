package de.mhus.vance.cli.debug;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.cli.VanceCliConfig;
import de.mhus.vance.cli.chat.ChatLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tiny unauthenticated HTTP server that exposes the CLI TUI for local
 * debugging and automation. Intended to run in {@code vance.profile=dev}
 * environments only — the caller must gate startup accordingly.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /debug/ui/history} — full history snapshot as JSON.</li>
 *   <li>{@code GET /debug/ui/history/latest?limit=N} — last {@code N}
 *       lines (default 20).</li>
 *   <li>{@code GET /debug/ui/input} — current input buffer content.</li>
 *   <li>{@code POST /debug/ui/input} — body
 *       {@code {"text":"...","submit":true}}. Sets the input buffer to
 *       {@code text}; if {@code submit} is true (default), triggers ENTER.</li>
 *   <li>{@code GET /debug/health} — trivial liveness probe.</li>
 * </ul>
 */
public final class DebugRestServer {

    private final DebugUiBridge bridge;
    private final String host;
    private final int port;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private @Nullable HttpServer server;

    public DebugRestServer(DebugUiBridge bridge, VanceCliConfig.Rest rest) {
        this.bridge = bridge;
        this.host = rest.getHost();
        this.port = rest.getPort();
    }

    /** Binds and starts the server. Idempotent: a second call is a no-op. */
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        HttpServer s = HttpServer.create(new InetSocketAddress(host, port), 0);
        s.createContext("/debug/health", exchange -> writeJson(exchange, 200, Map.of("ok", true)));
        s.createContext("/debug/ui/history", new HistoryHandler());
        s.createContext("/debug/ui/input", new InputHandler());
        // A cached pool keeps the server responsive without pinning a thread.
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "vance-debug-http");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        this.server = s;
    }

    /** Graceful shutdown. Safe to call multiple times. */
    public synchronized void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
            server = null;
        }
    }

    /** Bound address, or {@code null} if the server has not been started. */
    public synchronized @Nullable InetSocketAddress address() {
        HttpServer s = server;
        return s == null ? null : s.getAddress();
    }

    private final class HistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }
            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            if (path.endsWith("/latest")) {
                int limit = parseLimit(uri.getQuery(), 20);
                writeJson(exchange, 200, renderLines(bridge.historyTail(limit)));
            } else {
                writeJson(exchange, 200, renderLines(bridge.historySnapshot()));
            }
        }
    }

    private final class InputHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                writeJson(exchange, 200, Map.of("text", bridge.currentInput()));
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                writeJson(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }
            InputRequest req;
            try (InputStream body = exchange.getRequestBody()) {
                byte[] raw = body.readAllBytes();
                if (raw.length == 0) {
                    writeJson(exchange, 400, Map.of("error", "empty body"));
                    return;
                }
                req = mapper.readValue(raw, InputRequest.class);
            } catch (Exception e) {
                writeJson(exchange, 400, Map.of("error", "invalid json: " + e.getMessage()));
                return;
            }
            if (req.text == null) {
                writeJson(exchange, 400, Map.of("error", "field 'text' is required"));
                return;
            }
            boolean submit = req.submit == null ? true : req.submit;
            bridge.injectInput(req.text, submit);
            bridge.requestRedraw();
            writeJson(exchange, 200, Map.of("text", req.text, "submitted", submit));
        }
    }

    private List<Map<String, Object>> renderLines(List<ChatLine> lines) {
        List<Map<String, Object>> out = new ArrayList<>(lines.size());
        for (ChatLine line : lines) {
            // LinkedHashMap preserves key order in the JSON response.
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", line.timestamp().toString());
            m.put("level", line.level().name());
            m.put("text", line.text());
            out.add(m);
        }
        return out;
    }

    private static int parseLimit(@Nullable String query, int fallback) {
        if (query == null) {
            return fallback;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if ("limit".equals(part.substring(0, eq))) {
                try {
                    int v = Integer.parseInt(part.substring(eq + 1));
                    return Math.max(1, v);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** Wire shape for {@code POST /debug/ui/input}. */
    static final class InputRequest {
        public @Nullable String text;
        public @Nullable Boolean submit;
    }
}
