package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionCreateRequest;
import de.mhus.vance.api.ws.SessionCreateResponse;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /session-create <projectId>} — creates a new session in the given
 * project and binds it to the live connection. Allowed only on connections
 * that do not already have a bound session (Brain enforces).
 */
@Component
public class SessionCreateCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;

    public SessionCreateCommand(ConnectionService connection,
                                SessionService sessions,
                                ChatTerminal terminal) {
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "session-create";
    }

    @Override
    public String description() {
        return "Create a new session in a project and bind it. Args: <projectId>.";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.of("projectId", ArgKind.PROJECT));
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.size() != 1) {
            terminal.error("Usage: /session-create <projectId>");
            return;
        }
        String projectId = args.get(0);
        SessionCreateResponse response = connection.request(
                MessageType.SESSION_CREATE,
                SessionCreateRequest.builder().projectId(projectId).build(),
                SessionCreateResponse.class,
                Duration.ofSeconds(10));

        sessions.bind(response.getSessionId(), response.getProjectId());
        terminal.info("Session created: " + response.getSessionId()
                + " (project=" + response.getProjectId() + ")");

        // Auto-pick the brain-bootstrapped chat-process as active so
        // free-text input from the REPL routes there immediately —
        // mirrors what /session-bootstrap does, but for the simpler
        // single-arg /session-create entry point.
        String chatProcessName = response.getChatProcessName();
        if (chatProcessName != null && !chatProcessName.isBlank()) {
            sessions.setActiveProcess(chatProcessName);
            terminal.info("Active process: " + chatProcessName
                    + " (engine=" + response.getChatEngine() + ")");
        }
    }
}
