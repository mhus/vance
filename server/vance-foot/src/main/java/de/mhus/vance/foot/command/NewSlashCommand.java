package de.mhus.vance.foot.command;

import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.NewSessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /new [recipe]} — start a fresh session in the current project, the
 * text counterpart of the Web-UI {@code +} button. With a recipe name it
 * starts that recipe as the session chat, without it the brain resolves the
 * project default — same request the Web-UI modal sends.
 *
 * <p>For the interactive variant with the grouped recipe picker use
 * {@code /ui-new}. The project is the bound session's project, or the
 * configured {@code vance.bootstrap.project-id} when nothing is bound.
 */
@Component
public class NewSlashCommand implements SlashCommand {

    private final ConnectionService connection;
    private final NewSessionService newSessions;
    private final ChatTerminal terminal;

    public NewSlashCommand(ConnectionService connection, NewSessionService newSessions, ChatTerminal terminal) {
        this.connection = connection;
        this.newSessions = newSessions;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "new";
    }

    @Override
    public String description() {
        return "Start a new session in the current project. Args: [recipe]. See /ui-new for the picker.";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.of("recipe", ArgKind.FREE));
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (!connection.isOpen()) {
            terminal.error("Not connected — /connect first.");
            return;
        }
        String projectId = newSessions.resolveProject();
        if (projectId == null) {
            terminal.error("No project to start from — bind a session first "
                    + "(/session-create <projectId>) or set vance.bootstrap.project-id.");
            return;
        }
        @Nullable String recipe = args.isEmpty() ? null : args.get(0);
        try {
            newSessions.bootstrapNew(projectId, recipe);
        } catch (Exception e) {
            terminal.error("New session failed: " + e.getMessage());
        }
    }
}
