package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads the conversation in a session the Trillian opened itself (A6/D6) —
 * the "what did they answer" view, without waiting for an event.
 */
@Component
@RequiredArgsConstructor
public class SessionReadTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "sessionId",
                                    Map.of(
                                            "type", "string",
                                            "description", "A session you opened with session_open."),
                            "since",
                                    Map.of(
                                            "type", "string",
                                            "description", "Optional ISO-8601 instant — only lines after it.")),
            "required", List.of("sessionId"));

    private static final int LINE_LIMIT = 400;

    private final SessionService sessionService;
    private final ChatMessageService chatMessageService;

    @Override
    public String name() {
        return "session_read";
    }

    @Override
    public String description() {
        return "Read what has been said in a session you opened — who wrote "
                + "what, in order. Use it to catch up on answers; the reply "
                + "events only tell you that something arrived.";
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
        return Set.of("read-only");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        SessionDocument session = ownSession(params, ctx);
        if (session.getChatProcessId() == null) {
            throw new ToolException("session_read: session has no chat process");
        }
        String since = stringOrNull(params, "since");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ChatMessageDocument m : chatMessageService.activeHistoryWithInterim(
                session.getTenantId(), session.getSessionId(), session.getChatProcessId())) {
            if (m.getCreatedAt() != null
                    && since != null
                    && !m.getCreatedAt().isAfter(java.time.Instant.parse(since))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", m.getRole() == null ? null : m.getRole().name());
            if (m.getCreatedAt() != null) {
                row.put("at", m.getCreatedAt().toString());
            }
            String text = m.getContent() == null ? "" : m.getContent().strip();
            row.put("text", text.length() <= LINE_LIMIT ? text : text.substring(0, LINE_LIMIT) + "…");
            lines.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getSessionId());
        out.put("lines", lines);
        return out;
    }

    private SessionDocument ownSession(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("sessionId");
        if (!(raw instanceof String sessionId) || sessionId.isBlank()) {
            throw new ToolException("'sessionId' is required");
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
                    + "this Trillian opened itself can be read");
        }
        return session;
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
