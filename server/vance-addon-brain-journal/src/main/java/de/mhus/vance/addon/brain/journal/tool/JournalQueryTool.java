package de.mhus.vance.addon.brain.journal.tool;

import de.mhus.vance.addon.brain.journal.JournalEntry;
import de.mhus.vance.addon.brain.journal.JournalFolderReader;
import de.mhus.vance.addon.brain.journal.JournalService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Structured query over journal entries by date range, optionally filtered
 * by mood or tag. Complements {@code journal_search} (free text) with a
 * deterministic listing — e.g. "what did I write last week".
 */
@Component
@Slf4j
public class JournalQueryTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "The journal root folder (contains _app.yaml)."));
                put("from", Map.of("type", "string", "description", "ISO date lower bound (inclusive)."));
                put("to", Map.of("type", "string", "description", "ISO date upper bound (inclusive)."));
                put("mood", Map.of("type", "string"));
                put("tag", Map.of("type", "string"));
                put("limit", Map.of("type", "integer", "description", "Max entries (default 30)."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final JournalFolderReader folderReader;
    private final JournalService journalService;

    public JournalQueryTool(EddieContext eddieContext,
                            JournalFolderReader folderReader,
                            JournalService journalService) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
        this.journalService = journalService;
    }

    @Override public String name() { return "journal_query"; }

    @Override
    public String description() {
        return "List journal entries in a date range (from/to ISO dates), optionally "
                + "filtered by mood or tag. Deterministic — no LLM ranking. Returns date + "
                + "title + mood + tags per entry (no body).";
    }

    @Override public boolean primary() { return false; }

    @Override public Set<String> labels() {
        return Set.of("eddie", "read", "document", "journal");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");
        String from = paramString(params, "from");
        String to = paramString(params, "to");
        String mood = paramString(params, "mood");
        String tag = paramString(params, "tag");
        int limit = paramInt(params, "limit", 30);

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        JournalFolderReader.Scan scan = folderReader.scan(ctx.tenantId(), project.getName(), folder);

        List<Map<String, Object>> out = new ArrayList<>();
        for (JournalEntry e : journalService.listRange(scan, from, to)) {
            if (mood != null && !mood.equalsIgnoreCase(e.mood())) continue;
            if (tag != null && !e.tags().contains(tag)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", e.date());
            row.put("title", e.title());
            if (e.mood() != null) row.put("mood", e.mood());
            if (!e.tags().isEmpty()) row.put("tags", e.tags());
            out.add(row);
            if (out.size() >= limit) break;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", out.size());
        result.put("entries", out);
        return result;
    }

    private static @Nullable String paramString(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static int paramInt(Map<String, Object> params, String key, int fallback) {
        if (params == null) return fallback;
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* keep */ }
        }
        return fallback;
    }
}
