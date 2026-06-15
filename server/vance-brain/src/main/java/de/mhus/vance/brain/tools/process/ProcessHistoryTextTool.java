package de.mhus.vance.brain.tools.process;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Renders the chat history of another think-process as a single
 * Markdown transcript that the calling LLM can read as one context
 * block. Built for the "I need the details behind a worker's
 * summary" use-case: every worker terminates with a condensed
 * final reply (which arrives via {@code <process-event>}); when
 * the caller needs to know the actual reasoning trail, sources
 * consulted, or intermediate decisions, it pulls the full
 * transcript here as text — not as a list of structured records
 * to iterate over.
 *
 * <p>Tenant- and session-scoped by default. Resolves the target by
 * {@code name} (process name within the current session, as the
 * LLM sees it in {@code <process-event sourceProcessName="…">})
 * or {@code id} (Mongo id). Same resolution as
 * {@link ProcessStatusTool}.
 *
 * <p>Output layout:
 * <pre>
 * === &lt;name&gt; (&lt;engine&gt;) · &lt;firstAt&gt; → &lt;lastAt&gt; · status=&lt;status&gt; ===
 * [HH:mm:ss] USER: …
 * [HH:mm:ss] ASSISTANT: …
 *   ↳ tags: TOOL_CALL:research_search, RESOURCE:URL:…
 * …
 * </pre>
 *
 * <p>Truncation: hard cap on the rendered output via {@code
 * maxChars} (default 30 000). When the budget is exceeded the
 * oldest entries are dropped first and a {@code [… N earlier
 * messages truncated …]} marker is prepended — the caller still
 * sees the recent trail which is usually what's needed.
 */
@Component
@RequiredArgsConstructor
public class ProcessHistoryTextTool implements Tool {

    private static final int DEFAULT_MAX_CHARS = 30_000;
    private static final int MAX_CHARS_HARD_CAP = 200_000;
    private static final int CONTENT_TRIM = 4_000;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.ROOT)
                    .withZone(java.time.ZoneId.systemDefault());
    private static final DateTimeFormatter DAY_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.ROOT)
                    .withZone(java.time.ZoneId.systemDefault());

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("name", Map.of(
                        "type", "string",
                        "description",
                        "Process name within the current session — the same string "
                                + "you see as `sourceProcessName` in a <process-event> "
                                + "marker. Either `name` or `id` is required."));
                put("id", Map.of(
                        "type", "string",
                        "description",
                        "Mongo id (`sourceProcessId`) — alternative to `name` when "
                                + "the name is ambiguous across sessions."));
                put("roles", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string",
                                "enum", List.of("USER", "ASSISTANT", "SYSTEM")),
                        "description",
                        "Optional role filter. Default: USER + ASSISTANT (no system "
                                + "prompt noise). Set explicitly to include SYSTEM."));
                put("since", Map.of(
                        "type", "string",
                        "description",
                        "ISO-8601 timestamp — drop messages older than this. "
                                + "Useful when the transcript is long and only the "
                                + "tail matters."));
                put("includeArchived", Map.of(
                        "type", "boolean",
                        "description",
                        "When true, also include messages that have been rolled "
                                + "into a memory compaction (active+archived). "
                                + "Default false — only the live history is "
                                + "returned; compacted material lives under the "
                                + "process's ARCHIVED_CHAT memories."));
                put("maxChars", Map.of(
                        "type", "integer",
                        "description",
                        "Soft cap on the rendered transcript length. Older messages "
                                + "are dropped first when exceeded, with a truncation "
                                + "marker at the top. Default 30000, max 200000."));
            }},
            "required", List.of());

    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;

    @Override
    public String name() {
        return "process_history_text";
    }

    @Override
    public String description() {
        return "Read another think-process's chat history as a single Markdown "
                + "transcript. Use this when a `<process-event>` worker summary "
                + "is not enough and you need the reasoning trail, sources "
                + "consulted, or tool-call results. Returns one text block — "
                + "not a list to iterate over.";
    }

    @Override
    public boolean primary() {
        return false;
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
        String sessionId = ctx.sessionId();
        if (sessionId == null) {
            throw new ToolException("process_history_text requires a session scope");
        }
        if (params == null) params = Map.of();

        String name = stringParam(params, "name");
        String id = stringParam(params, "id");
        if ((name == null || name.isBlank()) && (id == null || id.isBlank())) {
            throw new ToolException("either 'name' or 'id' is required");
        }
        ThinkProcessDocument doc = resolve(ctx.tenantId(), sessionId, name, id);

        Set<ChatRole> roleFilter = parseRoles(params.get("roles"));
        Instant since = parseInstant(params.get("since"));
        boolean includeArchived = Boolean.TRUE.equals(params.get("includeArchived"));
        int maxChars = clampMaxChars(params.get("maxChars"));

        List<ChatMessageDocument> history = includeArchived
                ? chatMessageService.history(ctx.tenantId(), sessionId, doc.getId())
                : chatMessageService.activeHistory(ctx.tenantId(), sessionId, doc.getId());

        List<ChatMessageDocument> filtered = history.stream()
                .filter(m -> roleFilter.isEmpty() || roleFilter.contains(m.getRole()))
                .filter(m -> since == null
                        || (m.getCreatedAt() != null && !m.getCreatedAt().isBefore(since)))
                .toList();

        String transcript = render(doc, filtered, maxChars, includeArchived);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processName", doc.getName());
        out.put("processId", doc.getId());
        out.put("engine", doc.getThinkEngine());
        out.put("status", doc.getStatus() == null ? null : doc.getStatus().name());
        out.put("messageCount", filtered.size());
        out.put("transcript", transcript);
        return out;
    }

    // ───────────────────── helpers ─────────────────────

    private ThinkProcessDocument resolve(String tenantId, String sessionId,
                                         @Nullable String name, @Nullable String id) {
        if (id != null && !id.isBlank()) {
            return thinkProcessService.findById(id)
                    .filter(p -> tenantId.equals(p.getTenantId())
                            && sessionId.equals(p.getSessionId()))
                    .orElseThrow(() -> new ToolException(
                            "Process id '" + id + "' not found in current session"));
        }
        // Fallback to name + id-as-name (the LLM sometimes passes the
        // Mongo id in the `name` slot when it copied from sourceProcessId).
        return thinkProcessService.findByName(tenantId, sessionId, name)
                .or(() -> thinkProcessService.findById(name)
                        .filter(p -> tenantId.equals(p.getTenantId())
                                && sessionId.equals(p.getSessionId())))
                .orElseThrow(() -> new ToolException(
                        "Process '" + name + "' not found in current session"));
    }

    private static @Nullable String stringParam(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }

    private static Set<ChatRole> parseRoles(@Nullable Object raw) {
        // Default: USER + ASSISTANT — system prompts and raw tool turns
        // are usually noise for a transcript readout. Caller can override.
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Set.of(ChatRole.USER, ChatRole.ASSISTANT);
        }
        Set<ChatRole> out = new HashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s)) continue;
            try {
                out.add(ChatRole.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Tolerated — unknown roles are simply skipped rather
                // than erroring out the whole tool call.
            }
        }
        return out;
    }

    private static @Nullable Instant parseInstant(@Nullable Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            throw new ToolException("'since' must be ISO-8601 (e.g. 2026-06-15T08:00:00Z)");
        }
    }

    private static int clampMaxChars(@Nullable Object raw) {
        int n = DEFAULT_MAX_CHARS;
        if (raw instanceof Number num) n = num.intValue();
        else if (raw instanceof String s && !s.isBlank()) {
            try {
                n = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                n = DEFAULT_MAX_CHARS;
            }
        }
        if (n <= 0) n = DEFAULT_MAX_CHARS;
        return Math.min(n, MAX_CHARS_HARD_CAP);
    }

    /**
     * Build the rendered transcript. Walks chronologically; if the
     * running total exceeds {@code maxChars} the OLDEST entries are
     * elided and replaced with a {@code [… N earlier messages
     * truncated …]} marker, because the recent trail is usually what
     * the caller actually wants.
     */
    private static String render(ThinkProcessDocument doc,
                                 List<ChatMessageDocument> msgs,
                                 int maxChars,
                                 boolean includeArchived) {
        StringBuilder header = new StringBuilder();
        header.append("=== ").append(doc.getName());
        if (doc.getThinkEngine() != null && !doc.getThinkEngine().isBlank()) {
            header.append(" (").append(doc.getThinkEngine()).append(')');
        }
        Instant firstAt = msgs.isEmpty() ? null : msgs.get(0).getCreatedAt();
        Instant lastAt = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1).getCreatedAt();
        if (firstAt != null) header.append(" · ").append(DAY_STAMP.format(firstAt));
        if (lastAt != null && lastAt != firstAt) {
            header.append(" → ").append(STAMP.format(lastAt));
        }
        if (doc.getStatus() != null) {
            header.append(" · status=").append(doc.getStatus().name().toLowerCase(Locale.ROOT));
        }
        header.append(" · ").append(msgs.size()).append(" msgs");
        if (includeArchived) header.append(" (incl. archived)");
        header.append(" ===\n\n");

        if (msgs.isEmpty()) {
            return header.append("(no messages match the filter)\n").toString();
        }

        // Render bottom-up so we can stop once the budget is full and
        // know how many leading messages we had to drop.
        List<String> blocks = new java.util.ArrayList<>(msgs.size());
        for (ChatMessageDocument m : msgs) blocks.add(renderMessage(m));

        int budget = Math.max(0, maxChars - header.length()
                - 80 /* truncation marker reserve */);
        int kept = 0;
        int runningLen = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            int len = blocks.get(i).length();
            if (runningLen + len > budget && kept > 0) break;
            runningLen += len;
            kept++;
        }
        int dropped = blocks.size() - kept;

        StringBuilder out = new StringBuilder(header);
        if (dropped > 0) {
            out.append("[… ").append(dropped)
                    .append(" earlier messages truncated …]\n\n");
        }
        for (int i = blocks.size() - kept; i < blocks.size(); i++) {
            out.append(blocks.get(i));
        }
        return out.toString();
    }

    private static String renderMessage(ChatMessageDocument m) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        if (m.getCreatedAt() != null) sb.append(STAMP.format(m.getCreatedAt()));
        else sb.append("--:--:--");
        sb.append("] ");
        sb.append(m.getRole() == null ? "USER" : m.getRole().name());
        sb.append(":\n");
        String content = m.getContent();
        if (content != null && !content.isBlank()) {
            String trimmed = content.length() > CONTENT_TRIM
                    ? content.substring(0, CONTENT_TRIM)
                            + "\n[… message truncated, "
                            + (content.length() - CONTENT_TRIM)
                            + " more chars …]"
                    : content;
            for (String line : trimmed.split("\n", -1)) {
                sb.append("  ").append(line).append('\n');
            }
        } else {
            sb.append("  (empty)\n");
        }
        if (m.getTags() != null && !m.getTags().isEmpty()) {
            sb.append("  ↳ tags: ").append(String.join(", ", m.getTags())).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }
}
