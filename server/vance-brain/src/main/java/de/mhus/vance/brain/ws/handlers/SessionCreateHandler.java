package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionCreateRequest;
import de.mhus.vance.api.ws.SessionCreateResponse;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates a new session scoped to a project and atomically binds it to the
 * current connection. Only allowed on a session-less connection.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionCreateHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final SessionService sessionService;
    private final ProjectService projectService;
    private final ProjectManagerService projectManager;
    private final SessionConnectionRegistry connectionRegistry;

    @Override
    public String type() {
        return MessageType.SESSION_CREATE;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return !ctx.hasSession();
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        SessionCreateRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), SessionCreateRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, "Invalid session.create payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getProjectId())) {
            sender.sendError(wsSession, envelope, 400, "projectId is required");
            return;
        }

        Optional<ProjectDocument> project =
                projectService.findByTenantAndName(ctx.getTenantId(), request.getProjectId());
        if (project.isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Project '" + request.getProjectId() + "' not found");
            return;
        }
        ProjectDocument claimed = projectManager.claimForLocalPod(
                ctx.getTenantId(), project.get().getName());

        SessionDocument created = sessionService.create(
                ctx.getTenantId(),
                ctx.getUserId(),
                claimed.getName(),
                ctx.getDisplayName(),
                ctx.getClientType(),
                ctx.getClientVersion());

        boolean bound = sessionService.tryBind(
                created.getSessionId(), ctx.getConnectionId());
        if (!bound) {
            // Freshly created — nobody else could have bound it. If this ever
            // happens, surface the problem and leave the session in Mongo for
            // manual inspection / cleanup rather than silently closing it.
            log.warn("Freshly created session '{}' failed to bind", created.getSessionId());
            sender.sendError(wsSession, envelope, 500,
                    "Session created but could not be bound — please retry");
            return;
        }

        ctx.bindSession(created);
        connectionRegistry.register(created.getSessionId(), wsSession);
        SessionCreateResponse response = SessionCreateResponse.builder()
                .sessionId(created.getSessionId())
                .projectId(created.getProjectId())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.SESSION_CREATE, response);
    }

    private static boolean isBlank(@org.jspecify.annotations.Nullable String s) {
        return s == null || s.isBlank();
    }
}
