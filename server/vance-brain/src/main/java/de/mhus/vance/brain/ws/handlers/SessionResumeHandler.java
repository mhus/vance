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
    private final de.mhus.vance.brain.events.SessionRosterBroadcaster rosterBroadcaster;
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
        if (!doc.getTenantId().equals(ctx.getTenantId())) {
            sender.sendError(wsSession, envelope, 403,
                    "Session '" + request.getSessionId() + "' belongs to another tenant");
            return;
        }
        boolean isOwner = doc.getUserId().equals(ctx.getUserId());
        if (!isOwner && !doc.isAllowMultipleClients()) {
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

        // Bind strategy depends on who's joining:
        //  - Owner: same-user takeover from a fresh tab / pod —
        //    unconditional, the userId-match above guarantees we're
        //    the same human.
        //  - Non-owner on a shared session: try to claim the Mongo
        //    bind only if it's currently free or stale; never preempt
        //    the owner. If the owner is bound and fresh, we still
        //    accept the connection but it's "secondary" — no Mongo
        //    bind, just an entry in the connection registry so the
        //    broadcast paths reach it.
        boolean bound;
        if (isOwner) {
            bound = sessionService.tryBindWithUserTakeover(
                    doc.getSessionId(), ctx.getEditorId());
            if (!bound) {
                sender.sendError(wsSession, envelope, 409,
                        "Session '" + doc.getSessionId() + "' is closed or archived");
                return;
            }
        } else {
            // Best-effort: gladly take the bind if nobody holds it.
            // Failure is fine — we'll attach as a secondary participant.
            bound = sessionService.tryBind(
                    doc.getSessionId(), ctx.getEditorId());
        }

        ctx.bindSession(doc);
        SessionConnectionRegistry.RegisterResult registerResult = connectionRegistry.register(
                doc.getSessionId(),
                ctx.getUserId(),
                ctx.getEditorId(),
                ctx.getDisplayName(),
                wsSession,
                doc.isAllowMultipleClients());
        if (registerResult.outcome() == SessionConnectionRegistry.RegisterOutcome.REJECTED) {
            // Defensive: tryBindWithUserTakeover above gates same-user-only
            // resumes, so this should be unreachable for a private session.
            // If we do land here, fall back to a 409 so the client knows
            // not to retry blindly.
            sender.sendError(wsSession, envelope, 409,
                    "Session '" + doc.getSessionId()
                            + "' is private and already held by another user");
            ctx.unbindSession();
            return;
        }
        SessionConnectionRegistry.closeKicked(registerResult);
        // Initial roster push — see SessionCreateHandler for details.
        if (doc.isAllowMultipleClients()) {
            rosterBroadcaster.sendInitialRoster(doc.getSessionId(), wsSession);
        }
        // Owner-driven session-state changes: profile update +
        // resume cascade. A secondary participant joining a shared
        // session is just a viewer/contributor — they must not flip
        // the per-session profile (would mis-filter the owner's tool
        // surface) and must not wake suspended engines (only the owner
        // controls suspend/resume).
        if (isOwner) {
            thinkProcessService.updateBoundProfileForSession(
                    doc.getSessionId(), ctx.getProfile());
            try {
                sessionLifecycle.resumeSessionCascade(doc.getSessionId(), processEventEmitter);
            } catch (RuntimeException e) {
                log.warn("Resume cascade failed during session-resume sessionId='{}': {}",
                        doc.getSessionId(), e.toString());
            }
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
