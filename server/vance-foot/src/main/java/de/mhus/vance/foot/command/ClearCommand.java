package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@code /clear} — wipes the chat-render buffer and clears the visible screen. */
@Component
public class ClearCommand implements SlashCommand {

    private final ChatTerminal terminal;

    public ClearCommand(ChatTerminal terminal) {
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clear the chat output area.";
    }

    @Override
    public void execute(List<String> args) {
        terminal.clearScreen();
    }
}
