package de.mhus.vance.foot.debug;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.foot.command.ChatInputService;
import de.mhus.vance.foot.command.CommandService;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tiny unauthenticated HTTP server that exposes the Foot CLI for local
 * automation and self-testing. Bean is only created when
 * {@code vance.debug.rest.enabled=true}.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /debug/health} — liveness probe.</li>
 *   <li>{@code GET  /debug/state} — connection state, verbosity, UI mode.</li>
 *   <li>{@code GET  /debug/output?limit=N} — last N captured terminal lines.</li>
 *   <li>{@code POST /debug/command} body {@code {"line":"/connect"}} — invokes
 *       a slash command directly through {@link CommandService}. The line is
 *       auto-prefixed with {@code /} if missing — chat content is rejected
 *       here. Returns {@code {"line":"...","matched":true|false}}.</li>
 *   <li>{@code POST /debug/input} body {@code {"line":"/connect"}} or
 *       {@code {"line":"hello there"}} — full REPL replica. Slash → command,
 *       anything else → chat. Returns
 *       {@code {"kind":"COMMAND|CHAT","line":"...","ok":true|false,"error":...}}.</li>
 *   <li>{@code POST /debug/chat} body {@code {"line":"hello there"}} —
 *       sends the line as chat content unconditionally (no slash routing),
 *       routing it through the active think-process via
 *       {@link ChatInputService}. Same response shape as {@code /debug/input}
 *       but {@code kind} is always {@code CHAT}.</li>
 * </ul>
 *
 * <p>Together these endpoints make Foot fully remote-controllable: every
 * action a human can do at the JLine prompt is reachable via HTTP.
 */
@Component
@ConditionalOnProperty(prefix = "vance.debug.rest", name = "enabled", havingValue = "true")
public final class DebugRestServer {

    private final FootConfig config;
    private final ChatTerminal terminal;
    private final CommandService commandService;
    private final ChatInputService chatInputService;
    private final ConnectionService connectionService;
    private final InterfaceService interfaceService;
    private final SessionService sessionService;
    private final ObjectMapper json = JsonMapper.builder().build();

    private @Nullable HttpServer server;

    public DebugRestServer(FootConfig config,
                           ChatTerminal terminal,
                           CommandService commandService,
                           ChatInputService chatInputService,
                           ConnectionService connectionService,
                           InterfaceService interfaceService,
                           SessionService sessionService) {
        this.config = config;
        this.terminal = terminal;
        this.commandService = commandService;
        this.chatInputService = chatInputService;
        this.connectionService = connectionService;
        this.interfaceService = interfaceService;
        this.sessionService = sessionService;
    }

    @PostConstruct
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        FootConfig.Rest rest = config.getDebug().getRest();
        HttpServer s = HttpServer.create(new InetSocketAddress(rest.getHost(), rest.getPort()), 0);
        s.createContext("/debug/health", exchange -> writeJson(exchange, 200, Map.of("ok", true)));
        s.createContext("/debug/state", new StateHandler());
        s.createContext("/debug/output", new OutputHandler());
        s.createContext("/debug/command", new CommandHandler());
        s.createContext("/debug/input", new InputHandler());
        s.createContext("/debug/chat", new ChatHandler());
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "vance-foot-debug-http");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        this.server = s;
        terminal.info("Debug REST server listening on http://" + rest.getHost() + ":" + rest.getPort());
    }

    @PreDestroy
    public synchronized void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(0);
            server = null;
        }
    }

    private final class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("connection", connectionService.state().name());
            body.put("connectionOpen", connectionService.isOpen());
            body.put("verbosity", terminal.threshold().name());
            body.put("uiMode", interfaceService.mode().name());
            SessionService.BoundSession bound = sessionService.current();
            if (bound != null) {
                Map<String, String> sessionMap = new LinkedHashMap<>();
                sessionMap.put("sessionId", bound.sessionId());
                sessionMap.put("projectId", bound.projectId());
                body.put("session", sessionMap);
            } else {
                body.put("session", null);
            }
            writeJson(exchange, 200, body);
        }
    }

    private final class OutputHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }
            int limit = parseLimit(exchange.getRequestURI().getQuery(), 50);
            List<Map<String, Object>> lines = new ArrayList<>();
            for (ChatTerminal.Line line : terminal.tail(limit)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("timestamp", line.timestamp().toString());
                entry.put("level", line.level().name());
                entry.put("text", line.text());
                lines.add(entry);
            }
            writeJson(exchange, 200, lines);
        }
    }

    private final class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            CommandRequest req = readCommandRequest(exchange);
            if (req == null) {
                return;
            }
            String line = req.line.trim();
            if (!line.startsWith("/")) {
                line = "/" + line;
            }
            boolean matched = commandService.execute(line);
            writeJson(exchange, 200, Map.of("line", line, "matched", matched));
        }
    }

    /**
     * REPL replica: slash → command, anything else → chat. Mirrors what the
     * JLine prompt does, dispatched through the same {@link ChatInputService}
     * so behavior stays identical between human input and HTTP automation.
     */
    private final class InputHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            CommandRequest req = readCommandRequest(exchange);
            if (req == null) {
                return;
            }
            ChatInputService.InputResult result = chatInputService.submit(req.line);
            writeJson(exchange, 200, toJson(result));
        }
    }

    /**
     * Chat-only path: never routes through {@link CommandService} even if the
     * line starts with {@code /}. Useful for tests that want to feed literal
     * chat content (including slashes) to the active think-process.
     */
    private final class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            CommandRequest req = readCommandRequest(exchange);
            if (req == null) {
                return;
            }
            ChatInputService.InputResult result =
                    chatInputService.sendChat(req.line, ChatInputService.DEFAULT_CHAT_TIMEOUT);
            writeJson(exchange, 200, toJson(result));
        }
    }

    private @Nullable CommandRequest readCommandRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "method not allowed"));
            return null;
        }
        CommandRequest req;
        try (InputStream body = exchange.getRequestBody()) {
            byte[] raw = body.readAllBytes();
            if (raw.length == 0) {
                writeJson(exchange, 400, Map.of("error", "empty body"));
                return null;
            }
            req = json.readValue(raw, CommandRequest.class);
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("error", "invalid json: " + e.getMessage()));
            return null;
        }
        if (req.line == null || req.line.isBlank()) {
            writeJson(exchange, 400, Map.of("error", "field 'line' is required"));
            return null;
        }
        return req;
    }

    private static Map<String, Object> toJson(ChatInputService.InputResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", result.kind().name());
        body.put("line", result.line());
        body.put("ok", result.ok());
        body.put("error", result.error());
        // Backwards-compat alias so callers used to {/debug/command}.matched
        // can read the same field on the new endpoints — non-null only on
        // command results.
        if (result.kind() == ChatInputService.InputKind.COMMAND) {
            body.put("matched", result.ok());
        }
        return body;
    }

    private static int parseLimit(@Nullable String query, int fallback) {
        if (query == null) {
            return fallback;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if ("limit".equals(part.substring(0, eq))) {
                try {
                    return Math.max(1, Integer.parseInt(part.substring(eq + 1)));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** Wire shape for {@code POST /debug/command}. */
    static final class CommandRequest {
        public @Nullable String line;
    }
}
