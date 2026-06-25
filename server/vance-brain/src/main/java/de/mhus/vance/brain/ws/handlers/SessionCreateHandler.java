package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionCreateRequest;
import de.mhus.vance.api.ws.SessionCreateResponse;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.events.SessionConnectionRegistry;
import de.mhus.vance.brain.inbox.InboxPendingSummaryPusher;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.brain.project.ProjectManagerService.ClaimResult;
import de.mhus.vance.brain.session.SessionChatBootstrapper;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.List;
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
    private final ProjectLifecycleService lifecycleService;
    private final SessionConnectionRegistry connectionRegistry;
    private final SessionChatBootstrapper chatBootstrapper;
    private final ChatMessageService chatMessageService;
    private final InboxPendingSummaryPusher inboxSummaryPusher;
    private final HomeBootstrapService homeBootstrapService;
    private final RequestAuthority authority;
    private final ThinkProcessService thinkProcessService;

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
        authority.enforce(ctx,
                new Resource.Project(ctx.getTenantId(), request.getProjectId()), Action.START);

        // Lazy Hub-provisioning: a session-create against an unknown
        // _user_<login> project where <login> is a real user triggers
        // the Hub bootstrap on the spot. Robustness against logins
        // that didn't go through the AccessController bootstrap path.
        Optional<ProjectDocument> project = homeBootstrapService.resolveOrAutoProvision(
                ctx.getTenantId(), request.getProjectId());
        if (project.isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Project '" + request.getProjectId() + "' not found");
            return;
        }
        ClaimResult claim = projectManager.claimForLocalPodOrRedirect(
                ctx.getTenantId(), project.get().getName());
        if (claim instanceof ClaimResult.Redirect redirect) {
            sender.sendError(wsSession, envelope, 409,
                    "Project '" + project.get().getName()
                            + "' is owned by another brain process ("
                            + redirect.endpoint() + ")");
            return;
        }
        ProjectDocument claimed = ((ClaimResult.Local) claim).doc();

        // Promote the claimed row from "pinned to me in Mongo" to actually
        // running on this pod: workspace recovery + RECOVERING → RUNNING +
        // engine bootstrap. Without this, the project sits half-claimed
        // and the session attaches to a project whose Prak side-channel,
        // compaction, scheduler etc. never see it.
        // Idempotent on already-RUNNING and short-circuits to a no-op for
        // podless (_user_*, _vance) projects.
        claimed = lifecycleService.bring(claimed.getTenantId(), claimed.getName());

        SessionDocument created = sessionService.create(
                ctx.getTenantId(),
                ctx.getUserId(),
                claimed.getName(),
                ctx.getDisplayName(),
                ctx.getProfile(),
                ctx.getClientVersion(),
                ctx.getClientName());

        boolean bound = sessionService.tryBind(
                created.getSessionId(), ctx.getEditorId());
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
        SessionConnectionRegistry.RegisterResult registerResult = connectionRegistry.register(
                created.getSessionId(),
                ctx.getUserId(),
                ctx.getEditorId(),
                wsSession,
                created.isAllowMultipleClients());
        SessionConnectionRegistry.closeKicked(registerResult);

        // Heads-up: any pending inbox items? Pushed before other frames
        // so the client UI can render the counter early.
        inboxSummaryPusher.pushIfAny(wsSession, ctx.getTenantId(), ctx.getUserId());

        // Auto-spawn the session-chat think-process. Greeting is pushed
        // as chat-message-appended frames before the response so the
        // client renders it when the success ack arrives.
        ThinkProcessDocument chatProcess = null;
        try {
            chatProcess = chatBootstrapper.ensureChatProcess(created).orElse(null);
            if (chatProcess != null) {
                // Greeting + any history frames are dispatched by
                // ChatMessageNotificationDispatcher on chatMessageService.append.
                // No replay needed here.
            }
        } catch (RuntimeException e) {
            log.error("Chat-process bootstrap failed for session '{}'",
                    created.getSessionId(), e);
            // Session itself is fine — let the client connect; it just
            // won't have a default chat target. The chatProcessId fields
            // stay null in the response.
        }

        // Propagate the connection profile to every think-process on the
        // session so the per-turn tool filter (Tool.allowedForProfile)
        // sees the current bound profile. Done after chat-process spawn
        // so the freshly created process is included. See
        // engine-message-routing.md §4.1.1.
        thinkProcessService.updateBoundProfileForSession(
                created.getSessionId(), ctx.getProfile());

        SessionCreateResponse response = SessionCreateResponse.builder()
                .sessionId(created.getSessionId())
                .projectId(created.getProjectId())
                .chatProcessId(chatProcess == null ? null : chatProcess.getId())
                .chatProcessName(chatProcess == null ? null : chatProcess.getName())
                .chatEngine(chatProcess == null ? null : chatProcess.getThinkEngine())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.SESSION_CREATE, response);
    }

    private void pushAppendedMessages(
            WebSocketSession wsSession, ThinkProcessDocument process) throws IOException {
        List<ChatMessageDocument> full = chatMessageService.history(
                process.getTenantId(), process.getSessionId(), process.getId());
        for (ChatMessageDocument appended : full) {
            sender.sendNotification(wsSession, MessageType.CHAT_MESSAGE_APPENDED,
                    ChatMessageAppendedData.builder()
                            .chatMessageId(appended.getId())
                            .thinkProcessId(appended.getThinkProcessId())
                            .processName(process.getName())
                            .role(appended.getRole())
                            .content(appended.getContent())
                            .createdAt(appended.getCreatedAt())
                            .senderUserId(appended.getSenderUserId())
                            .senderDisplayName(appended.getSenderDisplayName())
                            .addressedToAgent(appended.isAddressedToAgent())
                            .build());
        }
    }

    private static boolean isBlank(@org.jspecify.annotations.Nullable String s) {
        return s == null || s.isBlank();
    }
}
