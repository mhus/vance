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
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
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
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates a think-process in the caller's bound session and kicks off its
 * lifecycle via {@link ThinkEngine#start}. Two paths — recipe-based or
 * direct-engine. See {@code specification/recipes.md}. Any chat messages
 * the engine produced during startup (typically a greeting) are pushed to
 * the client as {@link MessageType#CHAT_MESSAGE_APPENDED} notifications
 * before the ack.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessCreateHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final ChatMessageService chatMessageService;
    private final RecipeResolver recipeResolver;
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
        if (!isBlank(request.getRecipe()) && !isBlank(request.getEngine())) {
            log.info("process-create payload has both recipe='{}' and engine='{}' — recipe wins",
                    request.getRecipe(), request.getEngine());
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

        java.util.Optional<AppliedRecipe> applied;
        try {
            applied = recipeResolver.applyDefaulting(
                    tenantId, projectId,
                    request.getRecipe(), request.getEngine(),
                    ctx.getProfile(), request.getParams());
        } catch (RecipeResolver.UnknownRecipeException e) {
            sender.sendError(wsSession, envelope, 404, e.getMessage());
            return;
        } catch (RecipeResolver.UnknownEngineException e) {
            sender.sendError(wsSession, envelope, 404, e.getMessage());
            return;
        }

        ThinkProcessDocument created;
        try {
            created = applied.isPresent()
                    ? createFromRecipeApplied(request, tenantId, projectId, sessionId, applied.get())
                    : createFromEngine(request, tenantId, projectId, sessionId);
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException e) {
            sender.sendError(wsSession, envelope, 409, e.getMessage());
            return;
        } catch (UnknownEngineWsException e) {
            sender.sendError(wsSession, envelope, 404, e.getMessage());
            return;
        }

        try {
            thinkEngineService.start(created);
        } catch (RuntimeException e) {
            log.error("Engine start failed for process id='{}' engine='{}'",
                    created.getId(), created.getThinkEngine(), e);
            sender.sendError(wsSession, envelope, 500,
                    "Engine start failed: " + e.getMessage());
            return;
        }

        // CHAT_MESSAGE_APPENDED frames are pushed by the central
        // ChatMessageNotificationDispatcher on every chatMessageService.append.
        // No need to replay history here.
        ThinkProcessDocument refreshed = thinkProcessService.findById(created.getId())
                .orElse(created);
        ProcessCreateResponse response = ProcessCreateResponse.builder()
                .thinkProcessId(refreshed.getId())
                .name(refreshed.getName())
                .engine(refreshed.getThinkEngine())
                .status(refreshed.getStatus())
                .build();
        sender.sendReply(wsSession, envelope, MessageType.PROCESS_CREATE, response);
    }

    private ThinkProcessDocument createFromRecipeApplied(
            ProcessCreateRequest request, String tenantId,
            @org.jspecify.annotations.Nullable String projectId,
            String sessionId, AppliedRecipe applied) {
        ThinkEngine engine = thinkEngineService.resolve(applied.engine())
                .orElseThrow(() -> new UnknownEngineWsException(
                        "Recipe '" + applied.name() + "' references unknown engine '"
                                + applied.engine() + "'"));
        return thinkProcessService.create(
                tenantId, projectId, sessionId, request.getName(),
                engine.name(), engine.version(),
                request.getTitle(), request.getGoal(),
                /*parentProcessId*/ null,
                applied.params(),
                applied.name(),
                applied.promptOverride(),
                applied.promptOverrideSmall(),
                applied.promptMode(),
                applied.intentCorrection(),
                applied.dataRelayCorrection(),
                applied.effectiveAllowedTools(),
                applied.connectionProfile(),
                applied.defaultActiveSkills(),
                applied.allowedSkills() == null
                        ? null : java.util.Set.copyOf(applied.allowedSkills()));
    }

    private ThinkProcessDocument createFromEngine(
            ProcessCreateRequest request, String tenantId,
            @org.jspecify.annotations.Nullable String projectId,
            String sessionId) {
        ThinkEngine engine = thinkEngineService.resolve(request.getEngine())
                .orElseThrow(() -> new UnknownEngineWsException(
                        "Unknown think-engine '" + request.getEngine()
                                + "' — registered: " + thinkEngineService.listEngines()));
        return thinkProcessService.create(
                tenantId, projectId, sessionId, request.getName(),
                engine.name(), engine.version(),
                request.getTitle(), request.getGoal(),
                /*parentProcessId*/ null,
                request.getParams());
    }

    private static ChatMessageAppendedData toDto(ChatMessageDocument doc, String processName) {
        return ChatMessageAppendedData.builder()
                .chatMessageId(doc.getId())
                .thinkProcessId(doc.getThinkProcessId())
                .processName(processName)
                .role(doc.getRole())
                .content(doc.getContent())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    private static class UnknownEngineWsException extends RuntimeException {
        UnknownEngineWsException(String message) { super(message); }
    }
}
