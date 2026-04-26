package de.mhus.vance.foot.command;

import de.mhus.vance.foot.tools.ClientToolService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /tools-register} — re-announces the local client-tool set to
 * the brain. Useful while developing tools (the auto-trigger fires on
 * session-bind; this lets you push changes mid-session without
 * unbinding). Lists the names of the registered tools as a confirmation.
 */
@Component
public class ToolsRegisterCommand implements SlashCommand {

    private final ClientToolService clientTools;
    private final ChatTerminal terminal;

    public ToolsRegisterCommand(ClientToolService clientTools, ChatTerminal terminal) {
        this.clientTools = clientTools;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "tools-register";
    }

    @Override
    public String description() {
        return "Re-announce the local client-tool set to the brain. "
                + "Auto-fires on session-bind; this is the manual trigger.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        List<String> names = clientTools.toolNames();
        if (names.isEmpty()) {
            terminal.info("→ no client tools registered locally");
            return;
        }
        clientTools.registerAll();
        terminal.info("→ announced " + names.size() + " client tool(s) to brain: "
                + String.join(", ", names));
    }
}
