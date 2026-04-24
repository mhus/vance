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
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.cli.VanceCliConfig;
import de.mhus.vance.cli.chat.commands.ClearCommand;
import de.mhus.vance.cli.chat.commands.CommandContext;
import de.mhus.vance.cli.chat.commands.CommandRegistry;
import de.mhus.vance.cli.chat.commands.ConnectCommand;
import de.mhus.vance.cli.chat.commands.DisconnectCommand;
import de.mhus.vance.cli.chat.commands.HelpCommand;
import de.mhus.vance.cli.chat.commands.PingCommand;
import de.mhus.vance.cli.chat.commands.QuitCommand;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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

    private final CountDownLatch quitLatch = new CountDownLatch(1);

    private @Nullable HistoryView history;
    private @Nullable ProcessLoop processLoop;
    private @Nullable ConnectionManager connection;

    @Override
    public void run() {
        VanceCliConfig cfg = VanceCliConfig.load();

        try {
            ScreenCanvas screen = new ScreenCanvas("ChatScreen", 60, 10);
            HistoryView hist = new HistoryView("History");
            InputLine input = new InputLine("Input");
            this.history = hist;

            ProcessLoop loop = new ProcessLoop(screen);
            this.processLoop = loop;

            ConnectionManager conn = new ConnectionManager(cfg, new HistoryListener());
            this.connection = conn;

            StatusBar status = new StatusBar("Status", cfg, conn);

            Composite root = new Composite("ChatRoot",
                    screen.getWidth(), screen.getHeight(), new BorderLayout(0));
            root.addChild(status, PositionConstraint.NORTH);
            root.addChild(input, PositionConstraint.SOUTH);
            root.addChild(hist, PositionConstraint.CENTER);
            screen.setContent(root);

            CommandRegistry registry = buildRegistry();
            CommandContext ctx = new ChatContext(cfg, conn, registry);

            input.setOnSubmit(value -> registry.execute(value, ctx));
            screen.requestFocus(input);

            screen.registerShortcut("Ctrl+Q", this::quit);
            screen.registerShortcut("Ctrl+C", this::quit);

            hist.append(ChatLine.of(ChatLine.Level.INFO,
                    "Press Ctrl+Q or type /quit to exit. Type /help for commands."));

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

    private static CommandRegistry buildRegistry() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new HelpCommand());
        registry.register(new ConnectCommand());
        registry.register(new DisconnectCommand());
        registry.register(new PingCommand());
        registry.register(new ClearCommand());
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

    /** Bridges connection events into the history + status redraw. */
    private final class HistoryListener implements ConnectionManager.Listener {
        @Override
        public void onStateChanged(ConnectionManager.State state) {
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
            appendAndRedraw(ChatLine.Level.RECEIVED,
                    envelope.getType() + " " + String.valueOf(envelope.getData()));
        }
    }

    /** Adapter exposing this command's state to the command registry. */
    private final class ChatContext implements CommandContext {
        private final VanceCliConfig cfg;
        private final ConnectionManager conn;
        private final CommandRegistry registry;

        ChatContext(VanceCliConfig cfg, ConnectionManager conn, CommandRegistry registry) {
            this.cfg = cfg;
            this.conn = conn;
            this.registry = registry;
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
        public void quit() {
            ChatCommand.this.quit();
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
            StringBuilder sb = new StringBuilder(" vance chat — ");
            sb.append(connection.state().name().toLowerCase());
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
