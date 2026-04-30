package de.mhus.vance.foot.command;

import de.mhus.vance.foot.agent.ClientAgentDocService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /reload} — re-reads {@code ./agent.md} from the foot process's
 * cwd and re-uploads it to the brain. Auto-fires on session-bind; this
 * is the manual trigger after editing the file mid-session.
 */
@Component
public class ReloadAgentDocCommand implements SlashCommand {

    private final ClientAgentDocService agentDoc;
    private final ChatTerminal terminal;

    public ReloadAgentDocCommand(ClientAgentDocService agentDoc, ChatTerminal terminal) {
        this.agentDoc = agentDoc;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String description() {
        return "Re-read ./agent.md and re-upload it to the brain. "
                + "Auto-fires on session-bind.";
    }

    @Override
    public void execute(List<String> args) {
        Path resolved = agentDoc.resolvedPath();
        if (resolved == null) {
            terminal.info("→ no agent.md in cwd — nothing to upload");
            return;
        }
        boolean uploaded = agentDoc.uploadIfPresent();
        if (uploaded) {
            terminal.info("→ uploaded " + resolved + " to brain");
        } else {
            terminal.info("→ upload skipped (see logs)");
        }
    }
}
