package de.mhus.vance.foot.daemon;

import de.mhus.vance.api.tools.DaemonRegisterRequest;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.tools.ClientToolService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Sends {@link MessageType#DAEMON_REGISTER} after the welcome frame when
 * the foot runs with {@code profile=daemon}. Replaces the normal
 * session-bootstrap flow (which doesn't apply — daemons live outside
 * the session model).
 *
 * <p>Pulls the tool manifest from {@link ClientToolService} so the
 * daemon's exposed tools match exactly what a regular foot would expose
 * in a session. Re-call on manifest changes is supported via
 * {@link #reannounce} — used by hot-loaded plugins.
 */
@Component
@Slf4j
public class DaemonRegistrationService {

    private final FootConfig config;
    private final ChatTerminal terminal;
    private final ClientToolService clientTools;
    private final ConnectionService connection;

    /**
     * {@code @Lazy} on the constructor parameter (not on the field —
     * Lombok's {@code @RequiredArgsConstructor} doesn't propagate
     * field annotations to the generated constructor, so the lazy
     * proxy was getting lost in Spring Boot 4 and the cycle
     * ConnectionService → MessageDispatcher → handlers →
     * DaemonRegistrationService → ConnectionService re-emerged).
     */
    public DaemonRegistrationService(
            FootConfig config,
            ChatTerminal terminal,
            ClientToolService clientTools,
            @Lazy ConnectionService connection) {
        this.config = config;
        this.terminal = terminal;
        this.clientTools = clientTools;
        this.connection = connection;
    }

    /** Invoked by the welcome-handler after the connection comes up. */
    public void triggerAfterWelcome() {
        if (!Profiles.DAEMON.equals(config.getClient().getProfile())) {
            return;
        }
        register();
    }

    /** Re-send the manifest (e.g. after a plugin hot-load). */
    public void reannounce() {
        if (!Profiles.DAEMON.equals(config.getClient().getProfile())) {
            log.debug("DaemonRegistration.reannounce called but profile is not daemon — ignored");
            return;
        }
        register();
    }

    private void register() {
        if (connection == null || !connection.isOpen()) {
            terminal.warn("daemon-register: not connected, will retry on next reconnect");
            return;
        }
        String projectId = trim(config.getBootstrap() == null ? null
                : config.getBootstrap().getProjectId());
        String daemonName = trim(config.getClient().getName());
        if (projectId.isEmpty()) {
            terminal.error("daemon profile requires --project (none set in vance.bootstrap.project-id)");
            return;
        }
        if (daemonName.isEmpty()) {
            terminal.error("daemon profile requires --name (daemonName, used to identify this daemon in the project)");
            return;
        }

        List<ToolSpec> specs = clientTools.manifestSnapshot();
        DaemonRegisterRequest request = DaemonRegisterRequest.builder()
                .projectId(projectId)
                .daemonName(daemonName)
                .tools(specs)
                .build();

        try {
            connection.request(
                    MessageType.DAEMON_REGISTER, request, Object.class, Duration.ofSeconds(10));
            log.info("daemon-register OK project='{}' daemonName='{}' tools={}",
                    projectId, daemonName, specs.size());
            terminal.info("Daemon registered as '" + daemonName + "' in project '"
                    + projectId + "' (" + specs.size() + " tools)");
        } catch (Exception e) {
            log.warn("daemon-register failed: {}", e.toString());
            terminal.warn("daemon-register failed: " + e.getMessage());
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
