package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
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
 * The Trillian-specific "Level-B" observation tool: read the chat
 * transcript of another process in the same session — sub-sessions
 * that the Trillian User spawned, or the paired Control if you
 * want to see what the human said.
 *
 * <p>A human user would only see rendered chat in the UI. The
 * Trillian User reads the raw transcript including tool-call patterns
 * etc., which is what lets it observe sub-workers semi-live.
 *
 * <p>Same-session only — Nature-0 doesn't permit cross-session reads.
 */
@Component
@RequiredArgsConstructor
public class PeerReadChatMemoryTool implements Tool {

    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 200;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "processName", Map.of(
                            "type", "string",
                            "description", "Name of the target process in the current session."),
                    "limit", Map.of(
                            "type", "integer",
                            "description", "Max number of most-recent messages to return. "
                                    + "Default 30, capped at " + MAX_LIMIT + ".")),
            "required", List.of("processName"));

    private final TrillianInternalApi api;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String name() {
        return "peer_read_chat_memory";
    }

    @Override
    public String description() {
        return "Read the raw chat transcript of another process in this "
                + "session. Use it to observe a sub-session you spawned, "
                + "or to re-read what the human told Control.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null || ctx.sessionId() == null) {
            throw new ToolException("peer_read_chat_memory requires a session scope");
        }
        Object rawName = params == null ? null : params.get("processName");
        if (!(rawName instanceof String processName) || processName.isBlank()) {
            throw new ToolException("'processName' is required and must be non-empty");
        }
        int limit = DEFAULT_LIMIT;
        Object rawLimit = params == null ? null : params.get("limit");
        if (rawLimit instanceof Number n) {
            limit = Math.max(1, Math.min(MAX_LIMIT, n.intValue()));
        }

        Optional<ThinkProcessDocument> target = thinkProcessService.findByName(
                ctx.tenantId(), ctx.sessionId(), processName);
        if (target.isEmpty()) {
            throw new ToolException(
                    "Process '" + processName + "' not found in current session");
        }
        List<ChatMessageDocument> messages = api.readChatMemoryOf(
                ctx.processId(), target.get().getId(), limit);

        List<Map<String, Object>> shaped = new ArrayList<>(messages.size());
        for (ChatMessageDocument m : messages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", m.getRole() == null ? null : m.getRole().name());
            row.put("content", m.getContent());
            row.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
            shaped.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processName", processName);
        out.put("messages", shaped);
        out.put("count", shaped.size());
        return out;
    }
}
