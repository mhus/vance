package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.thinkprocess.ProcessCreateRequest;
import de.mhus.vance.api.thinkprocess.ProcessCreateResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.thinkprocess.ProcessSpawnService;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates a think-process in the caller's bound session and kicks off its
 * lifecycle via {@link ThinkEngine#start}. Recipe-driven only — engine is
 * always derived from the resolved recipe (cascade: caller recipe → bundled
 * {@code "default"}). See {@code specification/recipes.md}. Any chat
 * messages the engine produced during startup (typically a greeting) are
 * pushed to the client as {@link MessageType#CHAT_MESSAGE_APPENDED}
 * notifications before the ack.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessCreateHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final ProcessSpawnService spawnService;
    private final SessionService sessionService;
    private final RequestAuthority authority;

    @Override
    public String type() {
        return MessageType.PROCESS_CREATE;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessCreateRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessCreateRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, "Invalid process-create payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getName())) {
            sender.sendError(wsSession, envelope, 400, "name is required");
            return;
        }

        String tenantId = ctx.getTenantId();
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }
        String projectId = sessionService.findBySessionId(sessionId)
                .map(SessionDocument::getProjectId)
                .orElse(null);
        authority.enforce(ctx,
                new Resource.Session(tenantId, projectId == null ? "" : projectId, sessionId),
                Action.START);

        ThinkProcessDocument refreshed;
        try {
            refreshed = spawnService.spawn(ProcessSpawnService.SpawnRequest.builder()
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .sessionId(sessionId)
                    .name(request.getName())
                    .recipe(request.getRecipe())
                    .profile(ctx.getProfile())
                    .title(request.getTitle())
                    .goal(request.getGoal())
                    .params(request.getParams())
                    .build());
        } catch (ProcessSpawnService.UnknownTargetException e) {
            sender.sendError(wsSession, envelope, 404, e.getMessage());
            return;
        } catch (ProcessSpawnService.AlreadyExistsException e) {
            sender.sendError(wsSession, envelope, 409, e.getMessage());
            return;
        } catch (ProcessSpawnService.StartFailedException e) {
            sender.sendError(wsSession, envelope, 500, e.getMessage());
            return;
        }

        // CHAT_MESSAGE_APPENDED frames are pushed by the central
        // ChatMessageNotificationDispatcher on every chatMessageService.append.
        // No need to replay history here.
        ProcessCreateResponse response = ProcessCreateResponse.builder()
                .thinkProcessId(refreshed.getId())
                .name(refreshed.getName())
                .engine(refreshed.getThinkEngine())
                .status(refreshed.getStatus())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_CREATE, response);
    }

    private static ChatMessageAppendedData toDto(ChatMessageDocument doc, String processName) {
        return ChatMessageAppendedData.builder()
                .chatMessageId(doc.getId())
                .thinkProcessId(doc.getThinkProcessId())
                .processName(processName)
                .role(doc.getRole())
                .content(doc.getContent())
                .thinking(doc.getThinking())
                .createdAt(doc.getCreatedAt())
                .senderUserId(doc.getSenderUserId())
                .senderDisplayName(doc.getSenderDisplayName())
                .addressedToAgent(doc.isAddressedToAgent())
                .build();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
