package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /session-unbind} — releases the connection's binding to its session
 * without closing either. After this the connection is back to session-less
 * state, so {@code /session-create}, {@code /session-resume}, or
 * {@code /session-bootstrap} are allowed again. The session itself stays
 * OPEN and can be resumed later.
 */
@Component
public class SessionUnbindCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;

    public SessionUnbindCommand(ConnectionService connection,
                                SessionService sessions,
                                ChatTerminal terminal) {
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "session-unbind";
    }

    @Override
    public String description() {
        return "Release the binding between this connection and its session "
                + "without closing the session or the connection.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (!args.isEmpty()) {
            terminal.error("Usage: /session-unbind");
            return;
        }
        connection.request(
                MessageType.SESSION_UNBIND,
                null,
                Void.class,
                Duration.ofSeconds(10));
        sessions.clear();
        terminal.info("session unbound");
    }
}
