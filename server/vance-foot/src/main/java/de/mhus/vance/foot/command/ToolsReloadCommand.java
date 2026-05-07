package de.mhus.vance.foot.command;

import de.mhus.vance.foot.tools.pack.FootToolPackRegistry;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /tools-reload} — re-reads {@code ~/.vance/foot-tools/*.json},
 * rebuilds the pack-tool list (parses OpenAPI specs, runs MCP
 * {@code tools/list}, ...), and announces the union of bean-tools +
 * pack-tools to the brain. Synchronous — blocks the REPL until the
 * status line is ready.
 *
 * <p>Sibling of {@code /tools-register} which only re-announces what
 * already exists; this command also re-loads from disk first.
 */
@Component
public class ToolsReloadCommand implements SlashCommand {

    private final FootToolPackRegistry packs;
    private final ChatTerminal terminal;

    public ToolsReloadCommand(FootToolPackRegistry packs, ChatTerminal terminal) {
        this.packs = packs;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "tools-reload";
    }

    @Override
    public String description() {
        return "Re-read ~/.vance/foot-tools/*.json, rebuild the pack-tool list "
                + "(REST/OpenAPI + MCP), and re-announce to the brain.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        terminal.info("→ reloading foot tool packs ...");
        String status = packs.reload();
        terminal.info("→ " + status);
    }
}
