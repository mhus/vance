package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
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
 * Speaks to a session the Trillian opened itself (A6 / D6). The message is
 * delivered as a normal user turn — with the {@code @ai} mention prepended
 * automatically, because in a shared session a bare message does not reach
 * the engine at all ({@code multi-user-sessions.md} §2).
 *
 * <p>The answer arrives later; it surfaces as a {@code <session-reply>}
 * event in the loop's self-check stream (see
 * {@code TrillianSessionReplyListener}), and {@code session_read} shows the
 * whole conversation at any time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionSendTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "sessionId",
                                    Map.of(
                                            "type", "string",
                                            "description", "A session you opened with session_open."),
                            "message",
                                    Map.of(
                                            "type", "string",
                                            "description", "What to say there.")),
            "required", List.of("sessionId", "message"));

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final LaneScheduler laneScheduler;

    @Override
    public String name() {
        return "session_send";
    }

    @Override
    public String description() {
        return "Say something in a session you opened. Asynchronous: the "
                + "answer comes later as a session-reply event and is always "
                + "readable with session_read. The @ai mention is added for "
                + "you — in a shared session a bare message never reaches the "
                + "engine.";
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
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.userId() == null) {
            throw new ToolException("session_send requires a user scope");
        }
        SessionDocument session = ownSession(params, ctx);
        String message = stringOrThrow(params, "message");
        ThinkProcessDocument chat = chatOf(session);
        SteerMessage.UserChatInput input =
                new SteerMessage.UserChatInput(Instant.now(), /*messageId*/ null, ctx.userId(), addressed(message));
        try {
            laneScheduler.submit(chat.getId(), () -> thinkEngineService.steer(chat, input));
        } catch (RuntimeException e) {
            throw new ToolException(
                    "session_send: lane-submit failed for '" + session.getSessionId() + "': " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getSessionId());
        out.put("status", "sent");
        out.put(
                "next",
                "The answer arrives later as a session-reply event; "
                        + "session_read shows the conversation. Do not wait in this turn.");
        return out;
    }

    /**
     * The mention the engine needs to hear a message at all. Prepended
     * unconditionally when absent — harmless when the session is quiet,
     * fatal to forget when a human has joined.
     */
    static String addressed(String message) {
        String trimmed = message.stripLeading();
        return trimmed.startsWith("@") ? message : "@ai " + message;
    }

    /** Resolves the target and enforces the D6 contract: own sessions only. */
    private SessionDocument ownSession(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("sessionId");
        if (!(raw instanceof String sessionId) || sessionId.isBlank()) {
            throw new ToolException("'sessionId' is required and must be non-empty");
        }
        Optional<SessionDocument> sessionOpt = sessionService.findBySessionId(sessionId.trim());
        if (sessionOpt.isEmpty()) {
            throw new ToolException("Session '" + sessionId + "' not found");
        }
        SessionDocument session = sessionOpt.get();
        if (!session.getTenantId().equals(ctx.tenantId())) {
            throw new ToolException("Session '" + sessionId + "' is in another tenant");
        }
        if (!ctx.userId().equals(session.getUserId())) {
            throw new ToolException("Session '" + sessionId + "' is not yours — only sessions "
                    + "this Trillian opened itself can be steered");
        }
        return session;
    }

    private ThinkProcessDocument chatOf(SessionDocument session) {
        if (session.getChatProcessId() == null) {
            throw new ToolException("Session '" + session.getSessionId() + "' has no chat process");
        }
        return thinkProcessService
                .findById(session.getChatProcessId())
                .orElseThrow(() -> new ToolException("Session '" + session.getSessionId() + "' chat process is gone"));
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }
}
