package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                                            "description", "Optional ISO-8601 instant — only lines after it."),
                            "limit",
                                    Map.of(
                                            "type", "integer",
                                            "description", "How many of the newest lines (default 30, max 100).")),
            "required", List.of("sessionId"));

    private static final int LINE_LIMIT = 400;
    private static final int DEFAULT_LINES = 30;
    private static final int MAX_LINES = 100;

    private final de.mhus.vance.brain.trillian.TrillianOwnSessions ownSessions;
    private final ChatMessageService chatMessageService;

    @Override
    public String name() {
        return "session_read";
    }

    @Override
    public String description() {
        return "Read what has been said lately in a session you opened with session_open — "
                + "who wrote what, in order (newest lines, capped). Use it to catch up on "
                + "answers; the reply events only carry a preview.";
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
        Object raw = params == null ? null : params.get("sessionId");
        SessionDocument session = ownSessions.requireOwn(
                raw instanceof String sid ? sid : null, ctx, de.mhus.vance.shared.permission.Action.READ);
        if (session.getChatProcessId() == null) {
            throw new ToolException("session_read: session has no chat process");
        }
        java.time.Instant since = sinceOf(params);
        int limit = limitOf(params);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ChatMessageDocument m : chatMessageService.recentActive(
                session.getTenantId(),
                session.getSessionId(),
                session.getChatProcessId(),
                since,
                limit,
                /*withInterim*/ false)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", m.getRole() == null ? null : m.getRole().name());
            if (m.getSenderUserId() != null) {
                row.put("from", m.getSenderUserId());
            }
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

    private static java.time.@org.jspecify.annotations.Nullable Instant sinceOf(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("since");
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ToolException("session_read: 'since' must be an ISO-8601 instant like 2026-10-08T09:00:00Z", e);
        }
    }

    private static int limitOf(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("limit");
        int n = raw instanceof Number num ? num.intValue() : DEFAULT_LINES;
        return Math.max(1, Math.min(n, MAX_LINES));
    }
}
