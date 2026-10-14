package de.mhus.vance.foot.command;

import de.mhus.vance.foot.auth.FootAuthService;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code /logout} — drops the stored credentials ({@code .vancetope/access.yaml})
 * and closes the connection. A browser (OAuth) login is revoked at the server
 * first; when the server does not confirm that, the user is told the access may
 * still be live and where to revoke it. A password login's refresh token is a
 * self-expiring JWT with no server-side revocation, so there it is a local
 * delete (see {@code specification/public/oauth-server.md}).
 */
@Component
public class LogoutSlashCommand implements SlashCommand {

    private final FootAuthService auth;
    private final ConnectionService connection;
    private final ChatTerminal terminal;

    public LogoutSlashCommand(FootAuthService auth, @Lazy ConnectionService connection, ChatTerminal terminal) {
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
        return "Forget the stored token (.vancetope/access.yaml) and disconnect.";
    }

    @Override
    public void execute(List<String> args) {
        if (connection.isOpen()) {
            connection.disconnect("logout");
        }
        FootAuthService.LogoutResult result = auth.logout();
        if (result.serverRevokeFailed()) {
            terminal.warn("The server did not confirm revoking this browser login — the access may still be "
                    + "active there. Revoke it in the web UI under 'Apps with access to your account'.");
        }
        if (result.removed()) {
            terminal.info("Logged out — stored credentials removed.");
        } else {
            terminal.info("No stored credentials to remove.");
        }
    }
}
