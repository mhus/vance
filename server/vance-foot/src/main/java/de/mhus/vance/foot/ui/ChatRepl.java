package de.mhus.vance.foot.ui;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.foot.command.ChatInputService;
import de.mhus.vance.foot.command.SlashCompleter;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.session.SessionService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;
import org.jline.widget.AutosuggestionWidgets;
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
    private final SlashCompleter completer;
    private final PlanModeState planMode;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private @Nullable Terminal terminal;
    private @Nullable LineReader reader;

    public ChatRepl(ChatInputService input,
                    ChatTerminal chatTerminal,
                    InterfaceService interfaceService,
                    SessionService sessions,
                    StatusBar statusBar,
                    FootConfig config,
                    SlashCompleter completer,
                    PlanModeState planMode) {
        this.input = input;
        this.chatTerminal = chatTerminal;
        this.interfaceService = interfaceService;
        this.sessions = sessions;
        this.statusBar = statusBar;
        this.config = config;
        this.completer = completer;
        this.planMode = planMode;
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
                .completer(completer)
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
        // fish-shell-style ghost-text suggestion: while typing, JLine
        // shows the tail of the most-recent matching history entry
        // dimmed. AutosuggestionWidgets aliases forward-char /
        // end-of-line / forward-word so that pressing them at the end
        // of the line copies the suggested tail into the buffer
        // instead of just beeping. setAutosuggestion(HISTORY) alone
        // would only render the dim text without making it acceptable.
        AutosuggestionWidgets autosuggestion = new AutosuggestionWidgets(r);
        autosuggestion.enable();
        // Up/Down arrow defaults to "up-line-or-history" (walks all
        // history entries). Rebind to "history-search-backward" so the
        // arrows walk only history entries that share the typed prefix.
        // Same as readline's M-p / M-n behavior.
        rebindHistorySearch(r, t);
        // ESC during the prompt fires a process-stop for the active
        // chat-process. Works whenever readLine is active — chat
        // submission has been moved to the async path so this fires
        // even while the engine is "thinking".
        bindEscapeStop(r);
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
            // Chat content goes through the async dispatcher so the REPL
            // can return to readLine immediately and capture ESC even
            // while the engine is processing. Slash commands stay
            // synchronous inside submitFromRepl.
            input.submitFromRepl(line);
        }
    }

    /**
     * Bind raw {@code ESC} to a custom widget that fires
     * {@link ChatInputService#requestPause()}. JLine resolves multi-byte
     * sequences (arrow keys, etc.) before deciding what's a "lone" ESC,
     * so this binding only catches a real, single press.
     *
     * <p>ESC pauses active workers (non-CLOSED children of the
     * chat-process) — chat keeps going. The user follows up with a
     * correction in chat; Arthur decides via {@code process_resume}
     * + {@code process_steer} or a fresh {@code process_create}.
     */
    private void bindEscapeStop(LineReader r) {
        KeyMap<org.jline.reader.Binding> main = r.getKeyMaps().get(LineReader.MAIN);
        if (main == null) return;
        String widgetName = "vance-pause";
        Widget widget = () -> {
            input.requestPause();
            return true;
        };
        if (r instanceof LineReaderImpl impl) {
            impl.getWidgets().put(widgetName, widget);
            main.bind(new Reference(widgetName), KeyMap.esc());
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

    /**
     * Rebind Up/Down to prefix-search instead of unconditional history
     * walk. Looks up the terminfo capabilities for the arrow keys —
     * works whether the terminal sends {@code ESC [ A} (xterm-style) or
     * an alternate sequence. No-op when the capability strings are
     * unavailable for the current terminal.
     */
    private static void rebindHistorySearch(LineReader r, Terminal t) {
        KeyMap<org.jline.reader.Binding> main = r.getKeyMaps().get(LineReader.MAIN);
        if (main == null) return;
        String up = KeyMap.key(t, InfoCmp.Capability.key_up);
        String down = KeyMap.key(t, InfoCmp.Capability.key_down);
        if (up != null) {
            main.bind(new Reference(LineReader.HISTORY_SEARCH_BACKWARD), up);
        }
        if (down != null) {
            main.bind(new Reference(LineReader.HISTORY_SEARCH_FORWARD), down);
        }
    }

    private String prompt() {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            return "vance> ";
        }
        // Mode tag — only present while the active process is in a
        // non-NORMAL mode (Arthur Plan-Mode flow). Updates take effect
        // on the next readLine cycle, not mid-input — JLine fixes the
        // prompt for the duration of a readLine call.
        ProcessMode mode = planMode.mode(sessions.activeProcess());
        String suffix = mode == ProcessMode.NORMAL
                ? ""
                : ", " + mode.name().toLowerCase();
        return "vance(" + bound.sessionId() + suffix + ")> ";
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
