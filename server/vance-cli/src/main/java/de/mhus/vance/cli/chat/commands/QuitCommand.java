package de.mhus.vance.cli.chat.commands;

import java.util.List;

/** Closes the WebSocket if still open and shuts the TUI down. */
public class QuitCommand implements Command {

    @Override
    public String name() {
        return "quit";
    }

    @Override
    public List<String> aliases() {
        return List.of("q", "exit");
    }

    @Override
    public String description() {
        return "Disconnect and exit.";
    }

    @Override
    public String usage() {
        return "/quit";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        ctx.quit();
    }
}
