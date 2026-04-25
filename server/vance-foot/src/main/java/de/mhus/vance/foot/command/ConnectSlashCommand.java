package de.mhus.vance.foot.command;

import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /connect} — opens the WebSocket to the Brain. Uses the credentials
 * from {@code application.yaml}; per-call overrides come later when we add
 * a credentials prompt.
 */
@Component
public class ConnectSlashCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public ConnectSlashCommand(ConnectionService connection, ChatTerminal terminal) {
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "connect";
    }

    @Override
    public String description() {
        return "Open the WebSocket connection to the Brain.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        connection.connect();
    }
}
