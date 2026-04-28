package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.command.ChatInputService;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.session.SessionService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The JLine 3 REPL — default UI surface. Reads lines and forwards each one
 * to {@link ChatInputService}, which routes leading-slash input to the
 * command dispatcher and everything else to the brain as chat content.
 *
 * <p>The same {@link ChatInputService} backs the debug REST endpoints, so
 * remote-controlled flows hit the exact same code path as keyboard input.
 */
@Component
public class ChatRepl {

    private final ChatInputService input;
    private final ChatTerminal chatTerminal;
    private final InterfaceService interfaceService;
    private final SessionService sessions;
    private final StatusBar statusBar;
    private final FootConfig config;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private @Nullable Terminal terminal;
    private @Nullable LineReader reader;

    public ChatRepl(ChatInputService input,
                    ChatTerminal chatTerminal,
                    InterfaceService interfaceService,
                    SessionService sessions,
                    StatusBar statusBar,
                    FootConfig config) {
        this.input = input;
        this.chatTerminal = chatTerminal;
        this.interfaceService = interfaceService;
        this.sessions = sessions;
        this.statusBar = statusBar;
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
            // ChatInputService flips the PromptGate exclusive while the input
            // is being processed, so async streaming sinks can write directly
            // during this window without corrupting an active prompt.
            input.submit(line);
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
