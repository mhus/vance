package de.mhus.vance.foot.command;

import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code /help} — prints all registered slash commands. Uses {@code @Lazy}
 * for {@link CommandService} to break the circular construction (CommandService
 * is built from all SlashCommand beans, including this one).
 */
@Component
public class HelpCommand implements SlashCommand {

    private final CommandService commandService;
    private final ChatTerminal terminal;

    public HelpCommand(@Lazy CommandService commandService, ChatTerminal terminal) {
        this.commandService = commandService;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show available slash commands.";
    }

    @Override
    public void execute(List<String> args) {
        terminal.println(Verbosity.INFO, "Available commands:");
        for (SlashCommand cmd : commandService.all()) {
            terminal.println(Verbosity.INFO, "  /%-12s %s", cmd.name(), cmd.description());
        }
    }
}
