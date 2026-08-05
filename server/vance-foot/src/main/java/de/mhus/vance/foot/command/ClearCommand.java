package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionCreateRequest;
import de.mhus.vance.api.ws.SessionCreateResponse;
import de.mhus.vance.foot.auth.SessionAnchorStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /clear} — clears the visible screen and starts a fresh session
 * in the current project. The screen wipe is immediate; then the old
 * session is unbound and a new one created in the same project.
 *
 * <p>When no session is bound, only the screen is cleared.
 */
@Component
public class ClearCommand implements SlashCommand {

    private final ChatTerminal terminal;
    private final ConnectionService connection;
    private final SessionService sessions;
    private final SessionAnchorStore anchorStore;
    private final VancePaths paths;
    private final FootConfig config;

    public ClearCommand(ChatTerminal terminal,
                        ConnectionService connection,
                        SessionService sessions,
                        SessionAnchorStore anchorStore,
                        VancePaths paths,
                        FootConfig config) {
        this.terminal = terminal;
        this.connection = connection;
        this.sessions = sessions;
        this.anchorStore = anchorStore;
        this.paths = paths;
        this.config = config;
    }

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clear the screen and start a fresh session in the current project.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        terminal.clearScreen();

        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            return;
        }

        String projectId = bound.projectId();

        // The Brain only accepts session-create on connections that have
        // no bound session (SessionCreateHandler.canExecute = !hasSession).
        // /clear runs while a session IS bound, so we must unbind first.
        connection.request(
                MessageType.SESSION_UNBIND,
                null,
                Void.class,
                Duration.ofSeconds(10));
        sessions.clear();

        SessionCreateResponse response = connection.request(
                MessageType.SESSION_CREATE,
                SessionCreateRequest.builder().projectId(projectId).build(),
                SessionCreateResponse.class,
                Duration.ofSeconds(10));

        sessions.bind(response.getSessionId(), response.getProjectId());

        // Persist the new session anchor so that .vancetope/session.yaml
        // reflects the session created by /clear (mirrors AutoBootstrapService).
        anchorStore.upsertSession(
                paths.activeDir(),
                response.getSessionId(),
                response.getProjectId(),
                config.getClient().getName());

        String chatProcessName = response.getChatProcessName();
        if (chatProcessName != null && !chatProcessName.isBlank()) {
            sessions.setActiveProcess(chatProcessName);
        }

        terminal.info("New session: " + response.getSessionId()
                + " (project=" + response.getProjectId() + ")");
    }
}
