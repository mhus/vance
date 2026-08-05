package de.mhus.vance.foot.command;

import de.mhus.vance.foot.auth.SessionAnchorStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /name [text]} — set or show the local session name stored in
 * {@code .vancetope/sessions.yaml}. This is a purely local label — it
 * does not change the session's title on the server (use {@code /title}
 * for that). The name is used by {@code -c --name=<n>} to pick a
 * specific session from the history.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /name} — show the current session's local name.</li>
 *   <li>{@code /name <text>} — set the name (spaces allowed).</li>
 *   <li>{@code /name ""} — clear the name (set to null).</li>
 * </ul>
 */
@Component
public class NameCommand implements SlashCommand {

    private final SessionService sessions;
    private final SessionAnchorStore anchorStore;
    private final VancePaths paths;
    private final ChatTerminal terminal;

    public NameCommand(
            SessionService sessions,
            SessionAnchorStore anchorStore,
            VancePaths paths,
            ChatTerminal terminal) {
        this.sessions = sessions;
        this.anchorStore = anchorStore;
        this.paths = paths;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "name";
    }

    @Override
    public String description() {
        return "Set or show the local session name — usage: /name [text].";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            terminal.error("No session bound — use /session-resume or /session-bootstrap first.");
            return;
        }
        String sessionId = bound.sessionId();

        if (args.isEmpty()) {
            // Show current name.
            String currentName = anchorStore.findName(paths.activeDir(), sessionId);
            terminal.info("Name: " + (currentName != null ? currentName : "(unset)"));
            return;
        }

        String nameArg = String.join(" ", args).trim();
        // Empty quoted string → clear.
        String newName = nameArg.isEmpty() || nameArg.equals("\"\"") ? null : nameArg;

        anchorStore.renameSession(paths.activeDir(), sessionId, newName);
        terminal.info("Name: " + (newName != null ? newName : "(cleared)"));
    }
}
