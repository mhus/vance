package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bridge tool: post a chat message into an existing Trillian-Control
 * session, attributed to the calling user. Lets Arthur (or any
 * engine equipped with this tool) deliver follow-up context to a
 * Trillian it previously spawned via {@code trillian_session_create}.
 *
 * <p>Rejects sessions whose chat-process is not a Trillian-Control —
 * the bridge is single-purpose, not a generic cross-session steer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianSessionSendTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "sessionId", Map.of(
                            "type", "string",
                            "description", "The target Trillian-Control session id."),
                    "message", Map.of(
                            "type", "string",
                            "description", "The chat message to deliver.")),
            "required", List.of("sessionId", "message"));

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final LaneScheduler laneScheduler;

    @Override
    public String name() {
        return "trillian_session_send";
    }

    @Override
    public String description() {
        return "Deliver a chat message into a Trillian-Control session "
                + "you previously spawned. The Control process treats it "
                + "like a normal user message; the calling userId is "
                + "recorded as the writer.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.userId() == null) {
            throw new ToolException("trillian_session_send requires a user scope");
        }
        Object rawSession = params == null ? null : params.get("sessionId");
        Object rawMsg = params == null ? null : params.get("message");
        if (!(rawSession instanceof String sessionId) || sessionId.isBlank()) {
            throw new ToolException("'sessionId' is required and must be non-empty");
        }
        if (!(rawMsg instanceof String message) || message.isBlank()) {
            throw new ToolException("'message' is required and must be non-empty");
        }

        Optional<SessionDocument> sessionOpt = sessionService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new ToolException("Session '" + sessionId + "' not found");
        }
        SessionDocument session = sessionOpt.get();
        if (!session.getTenantId().equals(ctx.tenantId())) {
            throw new ToolException("Session '" + sessionId + "' is in another tenant");
        }
        if (session.getChatProcessId() == null) {
            throw new ToolException(
                    "Session '" + sessionId + "' has no chat-process yet");
        }
        Optional<ThinkProcessDocument> chatOpt =
                thinkProcessService.findById(session.getChatProcessId());
        if (chatOpt.isEmpty()) {
            throw new ToolException(
                    "Session '" + sessionId + "' chat-process id='"
                            + session.getChatProcessId() + "' is missing");
        }
        ThinkProcessDocument chat = chatOpt.get();
        // Engine-based check is Nature-agnostic (all trillian-* recipes
        // resolve to the trillian-control engine).
        if (!TrillianSessionBootstrapper.CONTROL_ENGINE_NAME.equals(chat.getThinkEngine())) {
            throw new ToolException(
                    "Session '" + sessionId + "' is not a Trillian-Control session "
                            + "(chat engine='" + chat.getThinkEngine() + "')");
        }

        SteerMessage.UserChatInput input = new SteerMessage.UserChatInput(
                Instant.now(),
                /*messageId*/ null,
                ctx.userId(),
                message);
        try {
            laneScheduler.submit(chat.getId(),
                    () -> thinkEngineService.steer(chat, input));
        } catch (RuntimeException e) {
            throw new ToolException(
                    "Lane-submit failed for chat process id='" + chat.getId()
                            + "': " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("controlProcessId", chat.getId());
        out.put("status", "delivered");
        return out;
    }
}
