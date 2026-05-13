package de.mhus.vance.foot.ui;

import de.mhus.vance.foot.command.ChatInputService;
import de.mhus.vance.foot.command.CommandService;
import de.mhus.vance.foot.command.SlashCommand;
import de.mhus.vance.foot.config.FootConfig;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * The REPL — default UI surface for {@code foot}. Wires the
 * {@link LiveRegion} (which owns input + render + animation) to the
 * {@link ChatInputService} that routes submissions to the brain.
 *
 * <p>JLine's {@code LineReader} is intentionally <em>not</em> used here.
 * Its raw-mode behaviour (OPOST off) breaks the Ink-style render
 * pattern foot now uses. See
 * {@code readme/foot-status-bar-rendering.md}.
 *
 * <p>History persistence and slash-command completion are wired here:
 * on startup we load the history file into {@link LiveRegion}, on each
 * submit we append to it, and a {@link LiveRegion.LiveCompleter}
 * backed by {@link CommandService} provides Tab + ghost-text
 * candidates.
 */
@Component
public class ChatRepl {

    private final ChatInputService input;
    private final ChatTerminal chatTerminal;
    private final InterfaceService interfaceService;
    private final LiveRegion liveRegion;
    private final FootConfig config;
    private final CommandService commandService;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private @Nullable Terminal terminal;
    private @Nullable Path historyFile;

    public ChatRepl(ChatInputService input,
                    ChatTerminal chatTerminal,
                    InterfaceService interfaceService,
                    LiveRegion liveRegion,
                    FootConfig config,
                    @Lazy CommandService commandService) {
        this.input = input;
        this.chatTerminal = chatTerminal;
        this.interfaceService = interfaceService;
        this.liveRegion = liveRegion;
        this.config = config;
        this.commandService = commandService;
    }

    public void requestStop() {
        stopRequested.set(true);
        // Also tell LiveRegion to release waitUntilStopped — otherwise
        // /quit just flips our flag and the run loop stays parked in
        // the inner wait.
        liveRegion.requestStop();
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    /**
     * Sets up the terminal, attaches the live region, and blocks until
     * the user quits.
     */
    public void run() throws IOException {
        Terminal t = TerminalBuilder.builder()
                .system(true)
                .dumb(true)
                .build();
        this.terminal = t;
        chatTerminal.attach(t);
        interfaceService.registerJlineTerminal(t);

        historyFile = resolveHistoryFile();
        liveRegion.loadHistory(loadHistoryFromFile(historyFile));
        liveRegion.setCompleter(this::completeSlashCommand);
        liveRegion.setSubmitListener(this::onSubmit);
        liveRegion.setInterruptListener(this::onInterrupt);
        liveRegion.setQuitListener(this::requestStop);
        liveRegion.attach(t);

        chatTerminal.info("Vance Foot — type /help for commands, Ctrl-D to exit.");
        if (historyFile != null) {
            chatTerminal.verbose("input history: " + historyFile);
        }

        try {
            while (!stopRequested.get()) {
                liveRegion.waitUntilStopped();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void onSubmit(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        appendHistoryFile(historyFile, line);
        echoSubmitted(line);
        input.submitFromRepl(line);
    }

    /**
     * Echo what the user just submitted as one or more inverse-video
     * (reverse) static lines, so it's visible above the brain's response
     * in the scrollback. Multi-line submits are emitted line-by-line —
     * SGR doesn't extend across {@code \n}, so wrapping each segment
     * separately is the safe layout.
     */
    private void echoSubmitted(String line) {
        String esc = "\u001b";
        for (String segment : line.split("\n", -1)) {
            liveRegion.emitStatic(esc + "[7m ❯ " + segment + " " + esc + "[0m");
        }
    }

    private void onInterrupt() {
        input.requestPause();
    }

    /**
     * Slash-command completer backed by {@link CommandService}: any word
     * starting with {@code /} matches command names by prefix. Used by
     * both Tab completion and ghost-text autosuggestion in
     * {@link LiveRegion}.
     */
    private List<String> completeSlashCommand(String input, int cursorIdx) {
        if (input == null || input.isEmpty() || !input.startsWith("/")) {
            return List.of();
        }
        // Only suggest on the first word of the line.
        int caret = Math.min(Math.max(cursorIdx, 0), input.length());
        int lineBegin = caret == 0 ? 0 : input.lastIndexOf('\n', caret - 1) + 1;
        String word = input.substring(lineBegin, caret);
        if (word.contains(" ")) return List.of();
        List<String> out = new ArrayList<>();
        for (SlashCommand cmd : commandService.all()) {
            String full = "/" + cmd.name();
            if (full.startsWith(word)) {
                out.add(full);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Resolves the history file path or returns {@code null} when
     * persistence is disabled. Defaults to {@code ~/.vance/foot-history};
     * an explicit {@code vance.history.file} value overrides, with
     * leading {@code ~/} expanded against {@code user.home}.
     */
    private @Nullable Path resolveHistoryFile() {
        FootConfig.History h = config.getHistory();
        if (!h.isEnabled()) return null;
        String home = System.getProperty("user.home");
        String configured = h.getFile();
        Path path;
        if (configured == null || configured.isBlank()) {
            if (home == null || home.isBlank()) return null;
            path = Path.of(home, ".vance", "foot-history");
        } else if (configured.startsWith("~/") && home != null && !home.isBlank()) {
            path = Path.of(home, configured.substring(2));
        } else {
            path = Path.of(configured);
        }
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            return null;
        }
        return path;
    }

    private static List<String> loadHistoryFromFile(@Nullable Path file) {
        if (file == null || !Files.exists(file)) return List.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void appendHistoryFile(@Nullable Path file, String line) {
        if (file == null || line == null || line.isEmpty() || line.contains("\n")) return;
        try {
            Files.writeString(file, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort; running without history is non-fatal
        }
    }

    @PreDestroy
    void shutdown() {
        liveRegion.detach();
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
