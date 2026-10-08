package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final de.mhus.vance.brain.trillian.TrillianOwnSessions ownSessions;

    @Override
    public String name() {
        return "session_send";
    }

    @Override
    public String description() {
        return "Say something in a session you opened with session_open. Asynchronous: the "
                + "answer comes later as a session-reply event and is always "
                + "readable with session_read. The @ai mention is added for "
                + "you; start the message with @<person> to address a human "
                + "there instead of the engine.";
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
        Object raw = params == null ? null : params.get("sessionId");
        SessionDocument session = ownSessions.requireOwn(
                raw instanceof String sid ? sid : null, ctx, de.mhus.vance.shared.permission.Action.EXECUTE);
        String message = stringOrThrow(params, "message");
        ThinkProcessDocument chat = ownSessions.chatOf(session);
        boolean toEngine = ownSessions.deliver(session, chat, ctx.userId(), ctx.processId(), message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getSessionId());
        out.put("status", toEngine ? "sent" : "posted");
        out.put(
                "next",
                toEngine
                        ? "The answer arrives later as a session-reply event; "
                                + "session_read shows the conversation. Do not wait in this turn."
                        : "Addressed to a person, not the engine — written into the chat only.");
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required");
        }
        return s.trim();
    }
}
