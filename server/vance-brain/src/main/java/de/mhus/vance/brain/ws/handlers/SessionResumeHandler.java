package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.SessionResumeResponse;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.inbox.InboxPendingSummaryPusher;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.project.ProjectManagerService.ClaimResult;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.api.session.SessionStatus;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class SessionResumeHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final SessionService sessionService;
    private final ProjectManagerService projectManager;
    private final SessionConnectionRegistry connectionRegistry;
    private final InboxPendingSummaryPusher inboxSummaryPusher;
    private final RequestAuthority authority;
    private final ThinkProcessService thinkProcessService;
    private final SessionLifecycleService sessionLifecycle;
    private final ProcessEventEmitter processEventEmitter;

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
        ClaimResult claim = projectManager.claimForLocalPodOrRedirect(
                doc.getTenantId(), doc.getProjectId());
        if (claim instanceof ClaimResult.Redirect redirect) {
            sender.sendError(wsSession, envelope, 409,
                    "Session '" + doc.getSessionId() + "' belongs to project '"
                            + doc.getProjectId() + "' on another brain process ("
                            + redirect.endpoint() + ")");
            return;
        }

        boolean bound = sessionService.tryBind(
                doc.getSessionId(), ctx.getConnectionId());
        if (!bound) {
            sender.sendError(wsSession, envelope, 409,
                    "Session '" + doc.getSessionId() + "' is already bound to another connection");
            return;
        }

        ctx.bindSession(doc);
        connectionRegistry.register(doc.getSessionId(), wsSession);
        // Propagate the connection profile to every think-process on the
        // session so the per-turn tool filter (Tool.allowedForProfile)
        // sees the current bound profile. See engine-message-routing.md
        // §4.1.1.
        thinkProcessService.updateBoundProfileForSession(
                doc.getSessionId(), ctx.getProfile());
        // Resume any engines that were left in SUSPENDED — idle-suspended
        // from before this reconnect, or FORCED-suspended at pod shutdown.
        // Without this, the user's first chat message after reconnect
        // would be appended to the pending queue and ignored (Lisbon
        // bug, 2026-05-26 — see planning/session-resume-cascade-bug.md).
        // sessionLifecycle.resumeSessionCascade is idempotent: a no-op
        // when nothing is suspended.
        try {
            sessionLifecycle.resumeSessionCascade(doc.getSessionId(), processEventEmitter);
        } catch (RuntimeException e) {
            // Resume failures must not block the bind reply — the
            // session is still usable for new turns, only the
            // pre-suspend pending may sit one more turn. Log and
            // continue.
            log.warn("Resume cascade failed during session-resume sessionId='{}': {}",
                    doc.getSessionId(), e.toString());
        }
        inboxSummaryPusher.pushIfAny(wsSession, ctx.getTenantId(), ctx.getUserId());
        // Look up the chat-process name (typically "chat") so the
        // client can set its active-process pointer in the same round
        // trip — same convenience SessionBootstrapResponse provides.
        String chatProcessName = null;
        if (doc.getChatProcessId() != null && !doc.getChatProcessId().isBlank()) {
            chatProcessName = thinkProcessService.findById(doc.getChatProcessId())
                    .map(p -> p.getName())
                    .orElse(null);
        }
        SessionResumeResponse response = SessionResumeResponse.builder()
                .sessionId(doc.getSessionId())
                .projectId(doc.getProjectId())
                .chatProcessName(chatProcessName)
                .build();
        sender.sendReply(wsSession, envelope, MessageType.SESSION_RESUME, response);
    }

    private static boolean isBlank(@org.jspecify.annotations.Nullable String s) {
        return s == null || s.isBlank();
    }
}
