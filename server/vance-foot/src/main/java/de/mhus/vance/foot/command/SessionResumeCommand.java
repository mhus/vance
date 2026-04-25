package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.SessionResumeResponse;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /session-resume <sessionId>} — resumes the named session and binds
 * it to the live connection. Updates {@link SessionService} so the prompt
 * and chat-send path see the new binding.
 */
@Component
public class SessionResumeCommand implements SlashCommand {

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;

    public SessionResumeCommand(ConnectionService connection,
                                SessionService sessions,
                                ChatTerminal terminal) {
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "session-resume";
    }

    @Override
    public String description() {
        return "Resume an existing session and bind it. Args: <sessionId>.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.size() != 1) {
            terminal.error("Usage: /session-resume <sessionId>");
            return;
        }
        String sessionId = args.get(0);
        SessionResumeResponse response = connection.request(
                MessageType.SESSION_RESUME,
                SessionResumeRequest.builder().sessionId(sessionId).build(),
                SessionResumeResponse.class,
                Duration.ofSeconds(10));

        sessions.bind(response.getSessionId(), response.getProjectId());
        terminal.info("Session resumed: " + response.getSessionId()
                + " (project=" + response.getProjectId() + ")");
    }
}
