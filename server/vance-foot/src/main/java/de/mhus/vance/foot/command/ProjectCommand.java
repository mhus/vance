package de.mhus.vance.foot.command;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.ProjectSwitchRequest;
import de.mhus.vance.api.ws.ProjectSwitchResponse;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /project [&lt;name&gt;]} — set the "spot" project Eddie
 * coordinates with. Without arguments shows the current spot;
 * {@code /project -} or {@code /project none} clears it. The command
 * is a direct WS round-trip ({@link MessageType#PROJECT_SWITCH}) — no
 * LLM turn — so the spot moves immediately without consuming tokens.
 *
 * <p>Mirrors the LLM-emitted {@code project_switch} brain tool. Both
 * paths land on the same atomic
 * {@code ThinkProcessService.setWorkingProjectId} write.
 */
@Component
public class ProjectCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public ProjectCommand(ConnectionService connection, ChatTerminal terminal) {
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "project";
    }

    @Override
    public String description() {
        return "Show or set the working project Eddie coordinates with. "
                + "Usage: /project [<name>]   ('-' or 'none' to clear)";
    }

    @Override
    public List<ArgSpec> argSpec() {
        return List.of(ArgSpec.of("name", ArgKind.PROJECT));
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.size() > 1) {
            terminal.error("Usage: /project [<name>]   ('-' or 'none' to clear)");
            return;
        }
        // No arg → query current state by sending null (server treats as a
        // probe, but with the current handler we'd actually clear it; to
        // preserve "show only" semantics we don't round-trip when args is
        // empty. The chat-message-appended hub-routing block already
        // surfaces the active spot on subsequent turns; this stays as a
        // simple "set" command.
        if (args.isEmpty()) {
            terminal.info("Usage: /project <name>   ('-' or 'none' to clear)");
            return;
        }
        String requested = args.get(0);

        ProjectSwitchResponse response = connection.request(
                MessageType.PROJECT_SWITCH,
                ProjectSwitchRequest.builder().name(requested).build(),
                ProjectSwitchResponse.class,
                Duration.ofSeconds(5));

        String effective = response == null ? null : response.getWorkingProject();
        if (effective == null || effective.isBlank()) {
            terminal.info("Working project cleared.");
        } else {
            terminal.info("Working project: " + effective);
        }
    }
}
