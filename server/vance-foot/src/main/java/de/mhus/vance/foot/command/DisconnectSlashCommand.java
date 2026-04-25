package de.mhus.vance.foot.command;

import de.mhus.vance.foot.connection.ConnectionService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /disconnect} — closes the WebSocket if open. No-op otherwise.
 */
@Component
public class DisconnectSlashCommand implements SlashCommand {

    private final ConnectionService connection;

    public DisconnectSlashCommand(ConnectionService connection) {
        this.connection = connection;
    }

    @Override
    public String name() {
        return "disconnect";
    }

    @Override
    public String description() {
        return "Close the WebSocket connection.";
    }

    @Override
    public void execute(List<String> args) {
        connection.disconnect(args.isEmpty() ? "user" : String.join(" ", args));
    }
}
