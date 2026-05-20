package de.mhus.vance.brain.daemon;

import de.mhus.vance.api.tools.DaemonRegisterRequest;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.Profiles;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.servertool.ServerToolRegistry;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.project.ProjectService;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles {@link MessageType#DAEMON_REGISTER}. The connection must have
 * authenticated with {@code profile=daemon}; the message carries the
 * target {@code projectId}, the project-unique {@code daemonName} and
 * the initial tool manifest.
 *
 * <p>Validation order (fail-fast, single error per attempt):
 *
 * <ol>
 *   <li>profile must be {@link Profiles#DAEMON}</li>
 *   <li>projectId must be non-blank and not {@code _user_*}</li>
 *   <li>projectId must resolve to an existing project (or be {@code _tenant})</li>
 *   <li>daemonName must match {@link #NAME_PATTERN}</li>
 *   <li>{@link DaemonRegistry#register} must succeed (no name collision)</li>
 * </ol>
 *
 * <p>Re-send on the same connection updates the manifest in place — used
 * by foot plugins that hot-load new tools.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DaemonRegisterHandler implements WsHandler {

    /**
     * Daemon-name validation: lowercase alphanumerics + {@code -_}, 1–64
     * chars. Same shape as profile names but slightly longer to allow
     * descriptive identifiers like {@code prod-eu-west-app01}.
     */
    static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final DaemonRegistry registry;
    private final ProjectService projectService;
    private final ServerToolRegistry serverToolRegistry;

    @Override
    public String type() {
        return MessageType.DAEMON_REGISTER;
    }

    /**
     * Daemons never bind a session, so the default
     * {@code ctx.hasSession()} gate would always reject. We accept iff
     * the handshake profile is {@code daemon}.
     */
    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return Profiles.DAEMON.equals(ctx.getProfile());
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        DaemonRegisterRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), DaemonRegisterRequest.class);
        } catch (RuntimeException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid daemon-register payload: " + e.getMessage());
            return;
        }
        if (request == null) {
            sender.sendError(wsSession, envelope, 400, "daemon-register payload missing");
            return;
        }

        String projectId = trim(request.getProjectId());
        String daemonName = trim(request.getDaemonName());

        if (projectId.isEmpty()) {
            sender.sendError(wsSession, envelope, 400, "projectId is required");
            return;
        }
        if (projectId.startsWith("_user_")) {
            sender.sendError(wsSession, envelope, 400,
                    "project '" + projectId + "' is user-scoped and does not support daemons "
                            + "(user-projects have no stable home pod)");
            return;
        }
        if (!HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)
                && projectService.findByTenantAndName(ctx.getTenantId(), projectId).isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "project '" + projectId + "' does not exist in tenant '"
                            + ctx.getTenantId() + "'");
            return;
        }
        if (!NAME_PATTERN.matcher(daemonName).matches()) {
            sender.sendError(wsSession, envelope, 400,
                    "daemonName must match " + NAME_PATTERN.pattern());
            return;
        }

        DaemonRegistry.DaemonKey key = new DaemonRegistry.DaemonKey(
                ctx.getTenantId(), projectId, daemonName);
        var registered = registry.register(
                key, wsSession,
                request.getTools() == null ? List.of() : request.getTools());
        if (registered.isEmpty()) {
            sender.sendError(wsSession, envelope, 409,
                    "daemon name '" + daemonName + "' is already registered in project '"
                            + projectId + "' by another connection — first-wins policy, "
                            + "your connection stays open but cannot expose tools under this name");
            return;
        }
        log.info("DaemonRegisterHandler {} accepted {} tools",
                key, registered.get().manifest().size());
        // Refresh foot_daemon-typed configs in the affected project so the
        // new sub-tools become visible without waiting for a discovery
        // cycle. No-op when the project's scope isn't loaded yet.
        try {
            serverToolRegistry.refreshProject(ctx.getTenantId(), projectId);
        } catch (RuntimeException e) {
            log.warn("daemon-register: failed to refresh server-tools for {} — sub-tools may "
                    + "remain hidden until next discovery: {}", key, e.toString());
        }
        sender.sendReply(wsSession, envelope, MessageType.DAEMON_REGISTER, null);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
