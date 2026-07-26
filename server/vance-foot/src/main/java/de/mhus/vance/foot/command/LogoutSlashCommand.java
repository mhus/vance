package de.mhus.vance.foot.command;

import de.mhus.vance.foot.auth.FootAuthService;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code /logout} — drops the stored credentials ({@code .vance/access.yaml})
 * and closes the connection. The refresh token is a self-expiring JWT with
 * no server-side revocation yet, so this is a local delete
 * (see {@code specification/cli-token-auth-plan.md}).
 */
@Component
public class LogoutSlashCommand implements SlashCommand {

    private final FootAuthService auth;
    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public LogoutSlashCommand(FootAuthService auth,
                              @Lazy ConnectionService connection,
                              ChatTerminal terminal) {
        this.auth = auth;
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "logout";
    }

    @Override
    public String description() {
        return "Forget the stored token (.vance/access.yaml) and disconnect.";
    }

    @Override
    public void execute(List<String> args) {
        if (connection.isOpen()) {
            connection.disconnect("logout");
        }
        boolean removed = auth.logout();
        if (removed) {
            terminal.info("Logged out — stored credentials removed.");
        } else {
            terminal.info("No stored credentials to remove.");
        }
    }
}
