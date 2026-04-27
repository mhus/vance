package de.mhus.vance.foot.ui;

import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.command.CommandService;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainException;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The JLine 3 REPL — default UI surface. Reads lines, routes leading-slash
 * input to {@link CommandService}, treats everything else as chat input.
 *
 * <p>Chat forwarding to the Brain is not yet implemented — for now chat lines
 * are echoed back through {@link ChatTerminal} so the loop is observable. The
 * real path goes through a {@code ChatService} that we add when the first
 * end-to-end Brain roundtrip is wired up.
 */
@Component
public class ChatRepl {

    private final CommandService commandService;
    private final ChatTerminal chatTerminal;
    private final InterfaceService interfaceService;
    private final SessionService sessions;
    private final ConnectionService connection;
    private final StatusBar statusBar;
    private final PromptGate promptGate;
    private final FootConfig config;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private @Nullable Terminal terminal;
    private @Nullable LineReader reader;

    public ChatRepl(CommandService commandService,
                    ChatTerminal chatTerminal,
                    InterfaceService interfaceService,
                    SessionService sessions,
                    ConnectionService connection,
                    StatusBar statusBar,
                    PromptGate promptGate,
                    FootConfig config) {
        this.commandService = commandService;
        this.chatTerminal = chatTerminal;
        this.interfaceService = interfaceService;
        this.sessions = sessions;
        this.connection = connection;
        this.statusBar = statusBar;
        this.promptGate = promptGate;
        this.config = config;
    }

    /** Allows {@code /quit} (or shutdown hooks) to exit the loop cleanly. */
    public void requestStop() {
        stopRequested.set(true);
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    /**
     * Runs the REPL until {@link #requestStop()} is called or the user signals
     * EOF (Ctrl-D). Sets up the JLine terminal once and tears it down at exit.
     */
    public void run() throws IOException {
        Terminal t = TerminalBuilder.builder()
                .system(true)
                .dumb(true)
                .build();
        this.terminal = t;
        chatTerminal.attach(t);
        interfaceService.registerJlineTerminal(t);

        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(t)
                .history(new DefaultHistory())
                .appName("vance-foot");
        Path historyFile = resolveHistoryFile();
        if (historyFile != null) {
            int max = Math.max(1, config.getHistory().getMaxEntries());
            builder.variable(LineReader.HISTORY_FILE, historyFile);
            builder.variable(LineReader.HISTORY_SIZE, max);
            builder.variable(LineReader.HISTORY_FILE_SIZE, max);
        }
        LineReader r = builder.build();
        // Bracketed paste keeps a multi-line clipboard paste from being
        // submitted as several separate Enter presses — the terminal wraps
        // the paste in special escape sequences and JLine collects the
        // whole block as one input. Default-on in JLine 3.x but some
        // terminals (older IntelliJ) need it pinned explicitly to not
        // accidentally disable it via fallback mode.
        r.setOpt(LineReader.Option.BRACKETED_PASTE);
        this.reader = r;
        chatTerminal.attachReader(r);
        statusBar.attach(t);

        chatTerminal.info("Vance Foot — type /help for commands, Ctrl-D to exit.");
        if (historyFile != null) {
            chatTerminal.verbose("input history: " + historyFile);
        }

        while (!stopRequested.get()) {
            String line;
            try {
                line = r.readLine(prompt());
            } catch (UserInterruptException ctrlC) {
                continue;
            } catch (EndOfFileException ctrlD) {
                break;
            }
            if (line == null || line.isBlank()) {
                continue;
            }
            // Mark the terminal exclusive while the REPL is processing the
            // line — chunk handlers and other async sinks may write directly
            // during this window. Outside it (during the readLine above) they
            // must go through printAbove to keep the prompt intact.
            promptGate.enterExclusive();
            try {
                if (line.startsWith("/")) {
                    commandService.execute(line);
                } else {
                    handleChat(line);
                }
            } finally {
                promptGate.exitExclusive();
            }
        }
    }

    /**
     * Resolves the history file path or returns {@code null} when persistence
     * is disabled. Defaults to {@code ~/.vance/foot-history}; an explicit
     * {@code vance.history.file} value overrides, with leading {@code ~/}
     * expanded against {@code user.home}. Best-effort: any IO trouble
     * (e.g. unwritable parent) silently disables persistence so the REPL
     * still starts.
     */
    private @Nullable Path resolveHistoryFile() {
        FootConfig.History h = config.getHistory();
        if (!h.isEnabled()) {
            return null;
        }
        String home = System.getProperty("user.home");
        String configured = h.getFile();
        Path path;
        if (configured == null || configured.isBlank()) {
            if (home == null || home.isBlank()) {
                return null;
            }
            path = Path.of(home, ".vance", "foot-history");
        } else if (configured.startsWith("~/") && home != null && !home.isBlank()) {
            path = Path.of(home, configured.substring(2));
        } else {
            path = Path.of(configured);
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            return null;
        }
        return path;
    }

    private String prompt() {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            return "vance> ";
        }
        return "vance(" + bound.sessionId() + ")> ";
    }

    private void handleChat(String line) {
        if (sessions.current() == null) {
            chatTerminal.error("No bound session — /connect, then /session-resume or /session-create.");
            return;
        }
        String process = sessions.activeProcess();
        if (process == null) {
            chatTerminal.error("No active process — /process <name> first, "
                    + "or use /process-steer <name> <message>.");
            return;
        }
        try {
            ProcessSteerResponse response = connection.request(
                    MessageType.PROCESS_STEER,
                    ProcessSteerRequest.builder()
                            .processName(process)
                            .content(line)
                            .build(),
                    ProcessSteerResponse.class,
                    Duration.ofSeconds(120));
            chatTerminal.verbose("→ steered " + response.getProcessName()
                    + " (status=" + response.getStatus() + ")");
        } catch (BrainException e) {
            chatTerminal.error(e.getMessage());
        } catch (Exception e) {
            chatTerminal.error("Steer failed: " + e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        LineReader r = reader;
        if (r != null) {
            try {
                r.getHistory().save();
            } catch (IOException ignored) {
                // best-effort: HISTORY_INCREMENTAL already saved per-line
            }
        }
        statusBar.detach();
        chatTerminal.attachReader(null);
        chatTerminal.attach(null);
        interfaceService.clearJlineTerminal();
        Terminal t = terminal;
        if (t != null) {
            try {
                t.close();
            } catch (IOException ignored) {
                // best-effort cleanup on shutdown
            }
        }
    }
}
