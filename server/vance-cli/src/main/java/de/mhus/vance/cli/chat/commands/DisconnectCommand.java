package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.cli.chat.ConnectionManager;

/** Closes the current WebSocket connection but keeps the TUI open. */
public class DisconnectCommand implements Command {

    @Override
    public String name() {
        return "disconnect";
    }

    @Override
    public String description() {
        return "Close the WebSocket but keep the TUI open.";
    }

    @Override
    public String usage() {
        return "/disconnect [reason]";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        ConnectionManager conn = ctx.connection();
        if (conn.state() == ConnectionManager.State.DISCONNECTED) {
            ctx.error("Not connected.");
            return;
        }
        String reason = args.length == 0 ? "disconnect" : String.join(" ", args);
        conn.disconnect(reason);
    }
}
