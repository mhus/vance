package de.mhus.vance.cli.chat.commands;

import de.mhus.vance.cli.chat.ConnectionManager;

/**
 * Opens the WebSocket connection. With no arguments, uses the credentials the
 * manager already has. With {@code /connect tenant user}, re-authenticates —
 * the password is still taken from the config file, since typing it into a
 * TUI line-edit has no masking yet.
 */
public class ConnectCommand implements Command {

    @Override
    public String name() {
        return "connect";
    }

    @Override
    public String description() {
        return "Open the WebSocket connection.";
    }

    @Override
    public String usage() {
        return "/connect [tenant] [user]";
    }

    @Override
    public void execute(CommandContext ctx, String[] args) {
        ConnectionManager conn = ctx.connection();
        if (conn.state() != ConnectionManager.State.DISCONNECTED) {
            ctx.error("Already " + conn.state().name().toLowerCase() + " — /disconnect first.");
            return;
        }

        if (args.length == 0) {
            ctx.info("Connecting as " + conn.credentials().tenant()
                    + "/" + conn.credentials().username() + " …");
            conn.connect();
            return;
        }
        if (args.length >= 1 && args.length <= 2) {
            String tenant = args[0];
            String username = args.length == 2 ? args[1] : conn.credentials().username();
            String password = conn.credentials().password();
            if (password.isBlank()) {
                ctx.error("No password available — set vance.auth.password in application.yaml.");
                return;
            }
            ctx.info("Connecting as " + tenant + "/" + username + " …");
            conn.connect(tenant, username, password);
            return;
        }
        ctx.error("Usage: " + usage());
    }
}
