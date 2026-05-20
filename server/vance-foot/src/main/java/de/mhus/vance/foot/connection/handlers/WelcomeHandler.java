package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.api.ws.WelcomeData;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.daemon.DaemonRegistrationService;
import de.mhus.vance.foot.ide.IntellijMcpRegistrationService;
import de.mhus.vance.foot.session.AutoBootstrapService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.Verbosity;
import de.mhus.vance.foot.ui.WindowTitleService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders the Brain's first frame after a successful handshake. The server
 * provides identity (user, tenant) and capability info; we surface it as
 * {@code INFO} for the user and capture full details at {@code DEBUG}.
 */
@Component
public class WelcomeHandler implements MessageHandler {

    private final ChatTerminal terminal;
    private final ConnectionService connection;
    private final AutoBootstrapService autoBootstrap;
    private final DaemonRegistrationService daemonRegistration;
    private final IntellijMcpRegistrationService intellijMcpRegistration;
    private final WindowTitleService windowTitle;
    private final ObjectMapper json = JsonMapper.builder().build();

    /**
     * {@code @Lazy} on {@link ConnectionService} breaks the cycle:
     * ConnectionService → MessageDispatcher → List&lt;MessageHandler&gt;
     * → WelcomeHandler → ConnectionService.
     */
    public WelcomeHandler(ChatTerminal terminal,
                          @Lazy ConnectionService connection,
                          AutoBootstrapService autoBootstrap,
                          DaemonRegistrationService daemonRegistration,
                          IntellijMcpRegistrationService intellijMcpRegistration,
                          WindowTitleService windowTitle) {
        this.terminal = terminal;
        this.connection = connection;
        this.autoBootstrap = autoBootstrap;
        this.daemonRegistration = daemonRegistration;
        this.intellijMcpRegistration = intellijMcpRegistration;
        this.windowTitle = windowTitle;
    }

    @Override
    public String messageType() {
        return MessageType.WELCOME;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        WelcomeData data = json.convertValue(envelope.getData(), WelcomeData.class);
        terminal.info("Welcome: tenant=" + data.getTenantId()
                + " user=" + data.getUserId()
                + (data.getDisplayName() == null ? "" : " (" + data.getDisplayName() + ")")
                + " — Brain " + data.getServer().getVersion()
                + " protocol v" + data.getServer().getProtocolVersion());
        terminal.println(Verbosity.DEBUG,
                "Server capabilities: " + data.getServer().getCapabilities()
                        + " — pingInterval=" + data.getServer().getPingInterval() + "s");
        connection.startKeepAlive(data.getServer().getPingInterval());
        windowTitle.setConnection(data.getTenantId());
        // Register IntelliJ MCP before bootstrap fires — bootstrap may
        // immediately need the IDE tools when the recipe references them.
        intellijMcpRegistration.registerIfConfigured();
        // Daemon registration runs alongside (it's a no-op when profile is
        // not 'daemon'). When profile IS 'daemon' the auto-bootstrap call
        // below short-circuits because daemon connections don't bind a
        // session — the SKIP_PROPERTY is set by the -d CLI shortcut.
        daemonRegistration.triggerAfterWelcome();
        autoBootstrap.triggerAfterWelcome();
    }
}
