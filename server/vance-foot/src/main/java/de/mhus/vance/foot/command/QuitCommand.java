package de.mhus.vance.foot.command;

import de.mhus.vance.foot.auth.SessionAnchorStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatRepl;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code /quit} (alias {@code /exit}) — leaves the REPL. The REPL loop checks
 * {@link ChatRepl#isStopRequested()} after every command and exits cleanly
 * when set; the Spring {@code @PreDestroy} chain then closes the WebSocket.
 */
@Component
public class QuitCommand implements SlashCommand {

    private final ChatRepl repl;
    private final SessionService sessions;
    private final ChatTerminal terminal;
    private final SessionAnchorStore anchorStore;
    private final VancePaths paths;
    private final FootConfig config;

    public QuitCommand(@Lazy ChatRepl repl,
                       SessionService sessions,
                       ChatTerminal terminal,
                       SessionAnchorStore anchorStore,
                       VancePaths paths,
                       FootConfig config) {
        this.repl = repl;
        this.sessions = sessions;
        this.terminal = terminal;
        this.anchorStore = anchorStore;
        this.paths = paths;
        this.config = config;
    }

    @Override
    public String name() {
        return "quit";
    }

    @Override
    public String description() {
        return "Exit the REPL.";
    }

    @Override
    public List<String> aliases() {
        return List.of("exit");
    }

    @Override
    public void execute(List<String> args) {
        SessionService.BoundSession bound = sessions.current();
        if (bound != null) {
            terminal.info("Disconnected from session " + bound.sessionId()
                    + " at " + resolveClientName(bound.sessionId()));
        }
        repl.requestStop();
    }

    /**
     * Resolves the client name to print alongside the disconnect notice.
     * Prefers the name persisted in the session anchor (the one the
     * bootstrap startup line announced and {@code /name} can change at
     * runtime), falling back to the configured {@code vance.client.name}
     * when no anchor entry exists yet.
     */
    private String resolveClientName(String sessionId) {
        @Nullable String stored = anchorStore.findName(paths.activeDir(), sessionId);
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        String configured = config.getClient().getName();
        return configured != null && !configured.isBlank() ? configured : "(unnamed)";
    }
}
