package de.mhus.vance.cli.chat;

import com.consolemaster.AnsiColor;
import com.consolemaster.AnsiFormat;
import com.consolemaster.BorderLayout;
import com.consolemaster.Canvas;
import com.consolemaster.Composite;
import com.consolemaster.Graphics;
import com.consolemaster.PositionConstraint;
import com.consolemaster.ProcessLoop;
import com.consolemaster.ScreenCanvas;
import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.cli.VanceCliConfig;
import de.mhus.vance.cli.chat.commands.ClearCommand;
import de.mhus.vance.cli.chat.commands.CommandContext;
import de.mhus.vance.cli.chat.commands.CommandRegistry;
import de.mhus.vance.cli.chat.commands.ConnectCommand;
import de.mhus.vance.cli.chat.commands.DisconnectCommand;
import de.mhus.vance.cli.chat.commands.HelpCommand;
import de.mhus.vance.cli.chat.commands.PingCommand;
import de.mhus.vance.cli.chat.commands.ProcessCreateCommand;
import de.mhus.vance.cli.chat.commands.ProcessSteerCommand;
import de.mhus.vance.cli.chat.commands.ProjectGroupListCommand;
import de.mhus.vance.cli.chat.commands.VerbosityCommand;
import de.mhus.vance.cli.chat.commands.ProjectListCommand;
import de.mhus.vance.cli.chat.commands.QuitCommand;
import de.mhus.vance.cli.chat.commands.SessionCreateCommand;
import de.mhus.vance.cli.chat.commands.SessionListCommand;
import de.mhus.vance.cli.chat.commands.SessionResumeCommand;
import de.mhus.vance.cli.debug.DebugRestServer;
import de.mhus.vance.cli.debug.DebugUiBridge;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code vance chat} — interactive TUI connected to the Brain over WebSocket.
 *
 * <p>Default layout is chat-style: status bar on top, scrolling history in the
 * middle, input line at the bottom. All slash-commands — local ({@code /help},
 * {@code /clear}, {@code /connect}, {@code /disconnect}, {@code /quit}) and
 * remote ({@code /ping}) — flow through the same {@link CommandRegistry}, so
 * whether an action lives client-side or sends a WebSocket envelope is a
 * per-command decision.
 *
 * <p>The TUI lifecycle is deliberately decoupled from the connection lifecycle:
 * the process loop runs until the user quits, and the {@link ConnectionManager}
 * can be disconnected and reconnected any number of times in between.
 */
@Command(
        name = "chat",
        description = "Open an interactive chat-style terminal against the Brain.")
public class ChatCommand implements Runnable {

    @Option(names = "--no-connect", description = "Start the TUI without auto-connecting.")
    private boolean noConnect;

    @Option(
            names = {"--config", "-c"},
            description = "Path to an alternative config YAML. Overrides $VANCE_CLI_CONFIG.")
    private @Nullable Path configPath;

    @Option(
            names = "--debug",
            description = "Force-enable the unauthenticated debug REST server, regardless of "
                    + "the vance.debug.rest.enabled config flag.")
    private boolean debugFlag;

    @Option(
            names = "--verbosity",
            description = "Initial verbosity level (0=chat only, 1=+status, 2=+wire trace). "
                    + "Default: 1. Change at runtime with /verbosity.")
    private int initialVerbosity = 1;

    private final CountDownLatch quitLatch = new CountDownLatch(1);
    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private @Nullable HistoryView history;
    private @Nullable ProcessLoop processLoop;
    private @Nullable ConnectionManager connection;
    private @Nullable ResponseRouter router;
    private @Nullable DebugRestServer debugServer;

    @Override
    public void run() {
        VanceCliConfig cfg = VanceCliConfig.load(configPath);

        try {
            ScreenCanvas screen = new ScreenCanvas("ChatScreen", 60, 10);
            HistoryView hist = new HistoryView("History");
            hist.setVerbosity(initialVerbosity);
            InputLine input = new InputLine("Input");
            this.history = hist;

            ProcessLoop loop = new ProcessLoop(screen);
            this.processLoop = loop;

            ResponseRouter responseRouter = new ResponseRouter(
                    id -> appendAndRedraw(ChatLine.Level.ERROR, "request timed out: " + id));
            this.router = responseRouter;

            // Force a redraw every frame + evict timed-out reply handlers. Without the
            // forced redraw, requestRedraw() calls from non-UI threads (WebSocket
            // callbacks) only become visible once the user hits a key, because
            // ProcessLoop sleeps between ticks and does not wake on the needsRedraw
            // flag. renderDifferences() is a no-op when nothing actually changed, so
            // the cost is minimal.
            loop.setUpdateCallback(() -> {
                responseRouter.evictExpired();
                loop.requestRedraw();
            });

            ConnectionManager conn = new ConnectionManager(cfg);
            HistoryListener historyListener = new HistoryListener(responseRouter);
            conn.addConnectionListener(historyListener);
            conn.addSessionListener(historyListener);
            this.connection = conn;

            StatusBar status = new StatusBar("Status", cfg, conn);

            Composite root = new Composite("ChatRoot",
                    screen.getWidth(), screen.getHeight(), new BorderLayout(0));
            root.addChild(status, PositionConstraint.NORTH);
            root.addChild(input, PositionConstraint.SOUTH);
            root.addChild(hist, PositionConstraint.CENTER);
            screen.setContent(root);

            CommandRegistry registry = buildRegistry();
            CommandContext ctx = new ChatContext(cfg, conn, registry, responseRouter);

            input.setOnSubmit(value -> registry.execute(value, ctx));
            screen.requestFocus(input);

            screen.registerShortcut("Ctrl+Q", this::quit);
            screen.registerShortcut("Ctrl+C", this::quit);

            hist.append(ChatLine.of(ChatLine.Level.INFO,
                    "Press Ctrl+Q or type /quit to exit. Type /help for commands."));

            startDebugServerIfEnabled(cfg, hist, input, loop);

            loop.startAsync();

            if (!noConnect) {
                conn.connect();
            } else {
                hist.append(ChatLine.of(ChatLine.Level.INFO,
                        "Started with --no-connect. Use /connect when ready."));
                loop.requestRedraw();
            }

            try {
                quitLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                DebugRestServer ds = debugServer;
                if (ds != null) {
                    ds.stop();
                }
                conn.shutdown();
                try {
                    loop.stop();
                } catch (IOException e) {
                    System.err.println("Failed to stop process loop: " + Errors.describe(e));
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to start chat UI: " + Errors.describe(e));
        }
    }

    private void startDebugServerIfEnabled(VanceCliConfig cfg, HistoryView hist,
            InputLine input, ProcessLoop loop) {
        VanceCliConfig.Rest rest = cfg.getDebug().getRest();
        boolean enabled = rest.isEnabled() || debugFlag;
        if (!enabled) {
            return;
        }
        DebugUiBridge bridge = new TuiBridge(hist, input, loop);
        DebugRestServer server = new DebugRestServer(bridge, rest);
        try {
            server.start();
            this.debugServer = server;
            hist.append(ChatLine.of(ChatLine.Level.INFO,
                    "debug REST listening on http://" + rest.getHost() + ":" + rest.getPort()));
        } catch (IOException e) {
            hist.append(ChatLine.of(ChatLine.Level.ERROR,
                    "debug REST failed to start: " + Errors.describe(e)));
        }
    }

    private static final class TuiBridge implements DebugUiBridge {
        private final HistoryView history;
        private final InputLine input;
        private final ProcessLoop loop;

        TuiBridge(HistoryView history, InputLine input, ProcessLoop loop) {
            this.history = history;
            this.input = input;
            this.loop = loop;
        }

        @Override
        public List<ChatLine> historySnapshot() {
            return history.snapshot();
        }

        @Override
        public List<ChatLine> historyTail(int limit) {
            return history.tail(limit);
        }

        @Override
        public String currentInput() {
            return input.currentText();
        }

        @Override
        public void injectInput(String text, boolean submit) {
            input.inject(text, submit);
        }

        @Override
        public void requestRedraw() {
            loop.requestRedraw();
        }
    }

    private static CommandRegistry buildRegistry() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new HelpCommand());
        registry.register(new ConnectCommand());
        registry.register(new DisconnectCommand());
        registry.register(new PingCommand());
        registry.register(new SessionListCommand());
        registry.register(new SessionCreateCommand());
        registry.register(new SessionResumeCommand());
        registry.register(new ProjectListCommand());
        registry.register(new ProjectGroupListCommand());
        registry.register(new ProcessCreateCommand());
        registry.register(new ProcessSteerCommand());
        registry.register(new ClearCommand());
        registry.register(new VerbosityCommand());
        registry.register(new QuitCommand());
        return registry;
    }

    private void quit() {
        quitLatch.countDown();
    }

    private void appendAndRedraw(ChatLine.Level level, String text) {
        HistoryView h = history;
        if (h != null) {
            h.append(ChatLine.of(level, text));
        }
        ProcessLoop l = processLoop;
        if (l != null) {
            l.requestRedraw();
        }
    }

    /** Bridges connection and session events into the history + status redraw. */
    private final class HistoryListener
            implements ConnectionLifecycleListener, SessionLifecycleListener {
        private final ResponseRouter router;

        HistoryListener(ResponseRouter router) {
            this.router = router;
        }

        @Override
        public void onStateChanged(ConnectionManager.State state) {
            // Drop pending handlers on disconnect so reconnect starts clean without
            // firing stale timeouts.
            if (state == ConnectionManager.State.DISCONNECTED) {
                router.clear();
            }
            ProcessLoop l = processLoop;
            if (l != null) {
                l.requestRedraw();
            }
        }

        @Override
        public void onInfo(String text) {
            appendAndRedraw(ChatLine.Level.INFO, text);
        }

        @Override
        public void onSystem(String text) {
            appendAndRedraw(ChatLine.Level.SYSTEM, text);
        }

        @Override
        public void onError(String text) {
            appendAndRedraw(ChatLine.Level.ERROR, text);
        }

        @Override
        public void onReceived(WebSocketEnvelope envelope) {
            if (router.tryDispatch(envelope)) {
                return;
            }
            // Server-initiated chat-message-appended gets rendered as a proper
            // chat line so the conversation reads naturally. We still fall
            // through to the verbatim RECEIVED log for anything else, which
            // the user can reveal by raising verbosity.
            if (MessageType.CHAT_MESSAGE_APPENDED.equals(envelope.getType())) {
                renderChatMessage(envelope);
                return;
            }
            // Unmatched server-initiated message or late reply — log verbatim.
            appendAndRedraw(ChatLine.Level.RECEIVED,
                    envelope.getType() + " " + String.valueOf(envelope.getData()));
        }

        private void renderChatMessage(WebSocketEnvelope envelope) {
            ChatMessageAppendedData data =
                    mapper.convertValue(envelope.getData(), ChatMessageAppendedData.class);
            if (data == null || data.getContent() == null) {
                appendAndRedraw(ChatLine.Level.RECEIVED,
                        envelope.getType() + " " + String.valueOf(envelope.getData()));
                return;
            }
            ChatRole role = data.getRole();
            String content = data.getContent();
            switch (role == null ? ChatRole.SYSTEM : role) {
                case USER -> appendAndRedraw(ChatLine.Level.CHAT_USER, "me: " + content);
                case ASSISTANT -> appendAndRedraw(ChatLine.Level.CHAT_ASSISTANT,
                        data.getProcessName() + ": " + content);
                case SYSTEM -> appendAndRedraw(ChatLine.Level.CHAT_SYSTEM,
                        "[" + data.getProcessName() + "] " + content);
            }
        }

        @Override
        public void onSessionBound(String sessionId, String projectId) {
            appendAndRedraw(ChatLine.Level.SYSTEM,
                    "session bound — " + sessionId + " (project=" + projectId + ")");
        }

        @Override
        public void onSessionUnbound() {
            appendAndRedraw(ChatLine.Level.SYSTEM, "session unbound");
        }
    }

    /** Adapter exposing this command's state to the command registry. */
    private final class ChatContext implements CommandContext {
        private final VanceCliConfig cfg;
        private final ConnectionManager conn;
        private final CommandRegistry registry;
        private final ResponseRouter router;

        ChatContext(VanceCliConfig cfg, ConnectionManager conn, CommandRegistry registry,
                ResponseRouter router) {
            this.cfg = cfg;
            this.conn = conn;
            this.registry = registry;
            this.router = router;
        }

        @Override
        public void info(String text) {
            appendAndRedraw(ChatLine.Level.INFO, text);
        }

        @Override
        public void system(String text) {
            appendAndRedraw(ChatLine.Level.SYSTEM, text);
        }

        @Override
        public void error(String text) {
            appendAndRedraw(ChatLine.Level.ERROR, text);
        }

        @Override
        public void sent(String text) {
            appendAndRedraw(ChatLine.Level.SENT, text);
        }

        @Override
        public void received(String text) {
            appendAndRedraw(ChatLine.Level.RECEIVED, text);
        }

        @Override
        public ConnectionManager connection() {
            return conn;
        }

        @Override
        public CommandRegistry registry() {
            return registry;
        }

        @Override
        public VanceCliConfig config() {
            return cfg;
        }

        @Override
        public void clearHistory() {
            HistoryView h = history;
            if (h != null) {
                h.clear();
            }
            ProcessLoop l = processLoop;
            if (l != null) {
                l.requestRedraw();
            }
        }

        @Override
        public int verbosity() {
            HistoryView h = history;
            return h == null ? 0 : h.verbosity();
        }

        @Override
        public void setVerbosity(int level) {
            HistoryView h = history;
            if (h != null) {
                h.setVerbosity(level);
            }
            ProcessLoop l = processLoop;
            if (l != null) {
                l.requestRedraw();
            }
        }

        @Override
        public void quit() {
            ChatCommand.this.quit();
        }

        @Override
        public void expectReply(String requestId, Consumer<WebSocketEnvelope> handler) {
            router.expectReply(requestId, handler);
        }

        @Override
        public void expectReply(String requestId, Consumer<WebSocketEnvelope> handler, long timeoutMs) {
            router.expectReply(requestId, handler, timeoutMs);
        }

        @Override
        public ObjectMapper mapper() {
            return mapper;
        }
    }

    /** Tiny status bar — single row at the top, reads live state from ConnectionManager. */
    private static final class StatusBar extends Canvas {
        private final VanceCliConfig cfg;
        private final ConnectionManager connection;

        StatusBar(String name, VanceCliConfig cfg, ConnectionManager connection) {
            super(name, 1, 1);
            this.cfg = cfg;
            this.connection = connection;
        }

        @Override
        public void paint(Graphics graphics) {
            graphics.clear();
            int w = getWidth();
            if (w <= 0) {
                return;
            }
            ConnectionManager.Credentials creds = connection.credentials();
            ConnectionManager.BoundSession bound = connection.boundSession();
            StringBuilder sb = new StringBuilder(" vance chat — ");
            sb.append(connection.state().name().toLowerCase());
            if (bound != null) {
                sb.append(" — project=").append(bound.projectId());
                sb.append(" session=").append(bound.sessionId());
            }
            sb.append(" — ").append(cfg.getBrain().getWsBase());
            sb.append(" — tenant=").append(creds.tenant());
            sb.append(" user=").append(creds.username());
            while (sb.length() < w) {
                sb.append(' ');
            }
            if (sb.length() > w) {
                sb.setLength(w);
            }
            graphics.drawStyledString(0, 0, sb.toString(),
                    AnsiColor.BLACK, AnsiColor.BRIGHT_CYAN, AnsiFormat.BOLD);
        }
    }
}
