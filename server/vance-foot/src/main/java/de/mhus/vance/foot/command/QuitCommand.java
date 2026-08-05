package de.mhus.vance.foot.command;

import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatRepl;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
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

    public QuitCommand(@Lazy ChatRepl repl,
                       SessionService sessions,
                       ChatTerminal terminal) {
        this.repl = repl;
        this.sessions = sessions;
        this.terminal = terminal;
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
            terminal.info("Disconnected from session " + bound.sessionId());
        }
        repl.requestStop();
    }
}
