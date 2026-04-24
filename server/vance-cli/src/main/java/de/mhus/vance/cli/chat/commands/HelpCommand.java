package de.mhus.vance.cli.chat.commands;

import java.util.List;

/**
 * Lists every registered command with its usage and description.
 */
public class HelpCommand implements Command {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public List<String> aliases() {
        return List.of("h", "?");
    }

    @Override
    public String description() {
        return "List available commands.";
    }

    @Override
    public String usage() {
        return "/help";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        int widest = 0;
        for (Command cmd : ctx.registry().all()) {
            widest = Math.max(widest, cmd.usage().length());
        }
        for (Command cmd : ctx.registry().all()) {
            String pad = " ".repeat(widest - cmd.usage().length());
            ctx.info(cmd.usage() + pad + "  — " + cmd.description());
        }
    }
}
