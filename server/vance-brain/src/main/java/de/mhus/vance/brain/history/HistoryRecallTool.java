package de.mhus.vance.brain.history;

import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Loads the full content of one or more past turns into the LLM context.
 * Use sparingly — each recalled turn costs the full message tokens.
 * Designed as the second stage after {@link HistorySearchTool}: search
 * cheaply, recall what matters.
 *
 * <p>Strictly scoped to the calling process: ids belonging to another
 * tenant or process are silently dropped (the underlying
 * {@link ChatMessageService#findByIds} enforces the filter).
 *
 * <p>Hard cap of {@link #MAX_RECALL} ids per call so a confused LLM
 * cannot blow the context window in one shot. Deferred — discovery
 * block + activation pattern, like {@link HistorySearchTool}.
 */
@Component
@RequiredArgsConstructor
public class HistoryRecallTool implements Tool {

    /** Hard cap on turns recalled per call. */
    public static final int MAX_RECALL = 10;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "turnIds", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                                    "Turn IDs returned by an earlier history_search call. "
                                            + "Max " + MAX_RECALL + " ids per call.")),
            "required", List.of("turnIds"));

    private final ChatMessageService chatMessageService;

    @Override public String name() { return "history_recall"; }

    @Override
    public String description() {
        return "Load full content of one or more past turns into context. "
                + "Use sparingly — each recalled turn costs the full message "
                + "tokens. Returns turns in chronological order.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }

    @Override
    public String searchHint() {
        return "read full content of earlier turns previously found via history_search";
    }

    @Override public Set<String> labels() { return Set.of("read-only"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null || ctx.processId().isBlank()) {
            throw new ToolException("history_recall requires a process scope");
        }
        Set<String> ids = parseIds(params);
        if (ids.size() > MAX_RECALL) {
            throw new ToolException("history_recall accepts at most " + MAX_RECALL
                    + " turnIds per call (got " + ids.size() + ")");
        }
        List<ChatMessageDocument> turns =
                chatMessageService.findByIds(ctx.tenantId(), ctx.processId(), ids);

        List<Map<String, Object>> out = new ArrayList<>(turns.size());
        for (ChatMessageDocument m : turns) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("turnId", m.getId());
            entry.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
            entry.put("role", m.getRole() == null ? null : m.getRole().name());
            entry.put("content", m.getContent());
            entry.put("tags", new ArrayList<>(m.getTags()));
            out.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("turns", out);
        result.put("count", out.size());
        return result;
    }

    private static Set<String> parseIds(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("turnIds");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ToolException("'turnIds' must be a non-empty string array");
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s);
            } else {
                throw new ToolException("'turnIds' entries must be non-blank strings");
            }
        }
        return out;
    }
}
