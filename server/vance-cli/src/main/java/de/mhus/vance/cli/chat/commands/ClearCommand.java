package de.mhus.vance.cli.chat.commands;

/** Wipes the history panel. */
public class ClearCommand implements Command {

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clear the history panel.";
    }

    @Override
    public String usage() {
        return "/clear";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        ctx.clearHistory();
    }
}
