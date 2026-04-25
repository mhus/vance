package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatRepl;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code /quit} — leaves the REPL. The REPL loop checks
 * {@link ChatRepl#isStopRequested()} after every command and exits cleanly
 * when set; the Spring {@code @PreDestroy} chain then closes the WebSocket.
 */
@Component
public class QuitCommand implements SlashCommand {

    private final ChatRepl repl;

    public QuitCommand(@Lazy ChatRepl repl) {
        this.repl = repl;
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
    public void execute(List<String> args) {
        repl.requestStop();
    }
}
