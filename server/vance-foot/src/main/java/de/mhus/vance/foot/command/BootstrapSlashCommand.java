package de.mhus.vance.foot.command;

import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.AutoBootstrapService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code /bootstrap} — manually re-fires the session bootstrap using the
 * current {@link FootConfig.Bootstrap} block. Recovery path for the race
 * window between WS welcome and auto-bootstrap where the connection can be
 * recycled mid-send. {@link AutoBootstrapService} already retries once
 * internally; this command exists for the cases where even that retry
 * couldn't recover (e.g. brain-side authority sweep that takes longer than
 * the 2 s wait window) and the user has since reconnected.
 */
@Component
public class BootstrapSlashCommand implements SlashCommand {

    private final FootConfig config;
    private final ConnectionService connection;
    private final AutoBootstrapService autoBootstrap;
    private final ChatTerminal terminal;

    public BootstrapSlashCommand(FootConfig config,
                                 ConnectionService connection,
                                 AutoBootstrapService autoBootstrap,
                                 ChatTerminal terminal) {
        this.config = config;
        this.connection = connection;
        this.autoBootstrap = autoBootstrap;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "bootstrap";
    }

    @Override
    public String description() {
        return "Re-run the session bootstrap from the current config.";
    }

    @Override
    public void execute(List<String> args) {
        if (!connection.isOpen()) {
            terminal.error("Not connected — /connect first.");
            return;
        }
        FootConfig.Bootstrap b = config.getBootstrap();
        if (b == null) {
            terminal.error("No vance.bootstrap block in config — nothing to bootstrap.");
            return;
        }
        if (b.getProjectId() == null && b.getSessionId() == null) {
            terminal.error("vance.bootstrap has no projectId/sessionId — set one in the config first.");
            return;
        }
        autoBootstrap.triggerNow();
    }
}
