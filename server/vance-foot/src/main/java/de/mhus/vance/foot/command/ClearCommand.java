package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.AutoBootstrapService;
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
    private final FootConfig config;
    private final AutoBootstrapService autoBootstrap;

    public ClearCommand(ChatTerminal terminal,
                        ConnectionService connection,
                        SessionService sessions,
                        FootConfig config,
                        AutoBootstrapService autoBootstrap) {
        this.terminal = terminal;
        this.connection = connection;
        this.sessions = sessions;
        this.config = config;
        this.autoBootstrap = autoBootstrap;
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

        FootConfig.Bootstrap bootstrap = config.getBootstrap();
        if (bootstrap == null) {
            bootstrap = new FootConfig.Bootstrap();
            config.setBootstrap(bootstrap);
        }
        bootstrap.setProjectId(projectId);
        bootstrap.setSessionId(null);
        autoBootstrap.triggerNow();
    }
}
