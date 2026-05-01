package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.SessionResumeResponse;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.inbox.InboxPendingSummaryPusher;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.api.session.SessionStatus;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Atomically binds an existing, unbound session to the current connection.
 * Only allowed on a session-less connection. The caller must own the target
 * session (same tenant + user).
 */
@Component
@RequiredArgsConstructor
public class SessionResumeHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final SessionService sessionService;
    private final ProjectManagerService projectManager;
    private final SessionConnectionRegistry connectionRegistry;
    private final InboxPendingSummaryPusher inboxSummaryPusher;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.SESSION_RESUME;
    }

    @Override
    public boolean canExecute(ConnectionContext ctx) {
        return !ctx.hasSession();
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        SessionResumeRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), SessionResumeRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, "Invalid session.resume payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getSessionId())) {
            sender.sendError(wsSession, envelope, 400, "sessionId is required");
            return;
        }

        Optional<SessionDocument> existing = sessionService.findBySessionId(request.getSessionId());
        if (existing.isEmpty() || existing.get().getStatus() == SessionStatus.CLOSED) {
            sender.sendError(wsSession, envelope, 404,
                    "Session '" + request.getSessionId() + "' not found");
            return;
        }
        SessionDocument doc = existing.get();
        if (!doc.getTenantId().equals(ctx.getTenantId())
                || !doc.getUserId().equals(ctx.getUserId())) {
            sender.sendError(wsSession, envelope, 403,
                    "Session '" + request.getSessionId() + "' belongs to another user");
            return;
        }
        authority.enforce(ctx,
                new Resource.Session(doc.getTenantId(), doc.getProjectId(), doc.getSessionId()),
                Action.START);
        projectManager.claimForLocalPod(doc.getTenantId(), doc.getProjectId());

        boolean bound = sessionService.tryBind(
                doc.getSessionId(), ctx.getConnectionId());
        if (!bound) {
            sender.sendError(wsSession, envelope, 409,
                    "Session '" + doc.getSessionId() + "' is already bound to another connection");
            return;
        }

        ctx.bindSession(doc);
        connectionRegistry.register(doc.getSessionId(), wsSession);
        inboxSummaryPusher.pushIfAny(wsSession, ctx.getTenantId(), ctx.getUserId());
        SessionResumeResponse response = SessionResumeResponse.builder()
                .sessionId(doc.getSessionId())
                .projectId(doc.getProjectId())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.SESSION_RESUME, response);
    }

    private static boolean isBlank(@org.jspecify.annotations.Nullable String s) {
        return s == null || s.isBlank();
    }
}
