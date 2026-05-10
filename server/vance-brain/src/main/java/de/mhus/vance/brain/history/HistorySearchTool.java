package de.mhus.vance.brain.history;

import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageSearchQuery;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Searches the calling process's chat history — including turns that
 * have already been rolled into a compaction memory — and returns turn
 * IDs plus short snippets. The LLM then calls {@link HistoryRecallTool}
 * to load full content of the turns it cares about.
 *
 * <p>Two-stage retrieval keeps the cost of "look up earlier turns"
 * predictable: the search is cheap (one Mongo query, small result map),
 * and only explicitly requested turns pay the recall token cost.
 *
 * <p>Deferred — only listed in the discovery block, activated via
 * {@code describe_tool}. See {@code planning/process-history-search.md}
 * §7.1.
 */
@Component
@RequiredArgsConstructor
public class HistorySearchTool implements Tool {

    /** Truncated content length surfaced in the search response. */
    static final int SNIPPET_CHARS = 200;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "tags", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                                    "Marker tags to filter by (AND across all listed tags). "
                                            + "Examples: FILE_EDIT, DOC_EDIT, "
                                            + "PLAN_STEP_STARTED:cleanup-debug, "
                                            + "RESOURCE:CLIENT_FILE:/abs/path/Foo.java, "
                                            + "TOOL_CALL:client_file_edit, ERROR, "
                                            + "MODE:plan."),
                    "query", Map.of(
                            "type", "string",
                            "description",
                                    "Optional full-text query against message content."),
                    "since", Map.of(
                            "type", "string",
                            "description",
                                    "Optional ISO-8601 timestamp (inclusive lower bound)."),
                    "limit", Map.of(
                            "type", "integer",
                            "description",
                                    "Maximum number of hits. Defaults to "
                                            + ChatMessageSearchQuery.DEFAULT_LIMIT
                                            + ", clamped to ["
                                            + 1
                                            + ", "
                                            + ChatMessageSearchQuery.MAX_LIMIT
                                            + "].")),
            "required", List.of());

    private final ChatMessageService chatMessageService;

    @Override public String name() { return "history_search"; }

    @Override
    public String description() {
        return "Search this process's past conversation history — including "
                + "turns no longer in the active context window. Returns turn "
                + "IDs plus short snippets; use history_recall to load full "
                + "turn content into context.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }

    @Override
    public String searchHint() {
        return "look up earlier turns, files edited, plan steps, "
                + "previous decisions, past tool calls in this process";
    }

    @Override public Set<String> labels() { return Set.of("read-only"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null || ctx.processId().isBlank()) {
            throw new ToolException("history_search requires a process scope");
        }
        Set<String> tagSet = parseStringArray(params, "tags");
        String text = stringOrNull(params, "query");
        Instant since = parseSince(params);
        int limit = parseLimit(params);

        ChatMessageSearchQuery q = new ChatMessageSearchQuery(
                ctx.tenantId(), ctx.processId(), tagSet, text, since, limit);
        List<ChatMessageDocument> hits = chatMessageService.search(q);

        List<Map<String, Object>> out = new ArrayList<>(hits.size());
        for (ChatMessageDocument m : hits) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("turnId", m.getId());
            entry.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
            entry.put("role", m.getRole() == null ? null : m.getRole().name());
            entry.put("tags", new ArrayList<>(m.getTags()));
            entry.put("snippet", snippet(m.getContent()));
            out.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hits", out);
        result.put("count", out.size());
        return result;
    }

    private static Set<String> parseStringArray(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return Set.of();
        if (!(raw instanceof List<?> list)) {
            throw new ToolException("'" + key + "' must be a string array");
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s);
            } else {
                throw new ToolException("'" + key + "' entries must be non-blank strings");
            }
        }
        return out;
    }

    private static java.lang.@org.jspecify.annotations.Nullable String stringOrNull(
            Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new ToolException("'" + key + "' must be a string");
        }
        return s.isBlank() ? null : s;
    }

    private static @org.jspecify.annotations.Nullable Instant parseSince(Map<String, Object> params) {
        String raw = stringOrNull(params, "since");
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ToolException("'since' must be an ISO-8601 timestamp: " + raw);
        }
    }

    private static int parseLimit(Map<String, Object> params) {
        Object v = params == null ? null : params.get("limit");
        if (v == null) return ChatMessageSearchQuery.DEFAULT_LIMIT;
        if (v instanceof Number n) return n.intValue();
        throw new ToolException("'limit' must be an integer");
    }

    private static String snippet(String content) {
        if (content == null) return "";
        if (content.length() <= SNIPPET_CHARS) return content;
        return content.substring(0, SNIPPET_CHARS) + "…";
    }
}
