package de.mhus.vance.brain.tools.kanban;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.document.kind.CardDocument;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Read-only query against a kanban-app folder. Returns a flat,
 * filtered, sorted card list — answers "what's in column doing?",
 * "what's assigned to alice?", "all blocked cards", "everything
 * tagged auth" without rebuilding any artefacts.
 *
 * <p>Doesn't write. Doesn't change board state.
 */
@Component
@Slf4j
public class KanbanAggregateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<String, Object>() {{
                put("folder", Map.of("type", "string",
                        "description", "Kanban app folder."));
                put("column", Map.of("type", "string",
                        "description", "Restrict to a single column."));
                put("columns", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Restrict to a list of columns. "
                                + "Combined with `column` as union."));
                put("assignee", Map.of("type", "string",
                        "description", "Match by assignee — exact, "
                                + "case-insensitive."));
                put("labels", Map.of("type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Match cards carrying ALL of these labels."));
                put("blocked", Map.of("type", "boolean",
                        "description", "true = only blocked cards, "
                                + "false = only non-blocked. Omit for both."));
                put("priority", Map.of("type", "string",
                        "description", "Match by priority — exact, "
                                + "case-insensitive (high/med/low/critical/…)."));
                put("includeBody", Map.of("type", "boolean",
                        "description", "Include card body in the result. "
                                + "Default false to keep the response small."));
                put("projectId", Map.of("type", "string"));
            }},
            "required", List.of("folder"));

    private final EddieContext eddieContext;
    private final KanbanFolderReader folderReader;

    public KanbanAggregateTool(EddieContext eddieContext,
                               KanbanFolderReader folderReader) {
        this.eddieContext = eddieContext;
        this.folderReader = folderReader;
    }

    @Override public String name() { return "kanban_aggregate"; }

    @Override
    public String description() {
        return "Query cards across a kanban board. Filter by column, "
                + "assignee, labels, blocked flag, or priority. "
                + "Returns a flat sorted list — read-only, never "
                + "writes. Use this when the user asks 'what's "
                + "open?', 'what does Alice have?', 'show blocked "
                + "cards', etc.";
    }

    @Override public boolean primary() { return false; }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "read", "document", "kanban", "query");
    }

    @Override
    public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String folder = paramString(params, "folder");
        if (folder == null) throw new ToolException("folder is required");

        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        KanbanFolderReader.Scan scan = folderReader.scan(
                ctx.tenantId(), project.getName(), normaliseFolder(folder));

        // Filters.
        Set<String> columnFilter = collectColumnFilter(params);
        String assigneeFilter = paramString(params, "assignee");
        List<String> labelFilter = paramStringList(params, "labels");
        Boolean blockedFilter = paramBoolean(params, "blocked");
        String priorityFilter = paramString(params, "priority");
        boolean includeBody = paramBoolean(params, "includeBody") == Boolean.TRUE;

        List<Map<String, Object>> out = new ArrayList<>();
        for (KanbanFolderReader.CardFile cf : scan.cards()) {
            if (!matches(cf, columnFilter, assigneeFilter, labelFilter,
                    blockedFilter, priorityFilter)) continue;
            out.add(toMap(cf, includeBody));
        }

        out.sort((a, b) -> {
            String ca = (String) a.get("column");
            String cb = (String) b.get("column");
            int cmp = ca == null ? (cb == null ? 0 : 1) : (cb == null ? -1 : ca.compareTo(cb));
            if (cmp != 0) return cmp;
            String ta = (String) a.get("title");
            String tb = (String) b.get("title");
            return ta == null ? (tb == null ? 0 : 1) : (tb == null ? -1 : ta.compareTo(tb));
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("folder", scan.folder());
        result.put("cardCount", out.size());
        result.put("cards", out);
        return result;
    }

    private static boolean matches(KanbanFolderReader.CardFile cf,
                                   Set<String> columnFilter,
                                   @Nullable String assigneeFilter,
                                   List<String> labelFilter,
                                   @Nullable Boolean blockedFilter,
                                   @Nullable String priorityFilter) {
        if (!columnFilter.isEmpty() && !columnFilter.contains(cf.column())) return false;
        CardDocument card = cf.card();
        if (assigneeFilter != null) {
            if (card.assignee() == null) return false;
            if (!card.assignee().equalsIgnoreCase(assigneeFilter)) return false;
        }
        if (priorityFilter != null) {
            if (card.priority() == null) return false;
            if (!card.priority().equalsIgnoreCase(priorityFilter)) return false;
        }
        if (blockedFilter != null) {
            boolean isBlocked = card.blocked() || card.labels().stream()
                    .anyMatch(l -> "blocked".equalsIgnoreCase(l));
            if (blockedFilter != isBlocked) return false;
        }
        if (!labelFilter.isEmpty()) {
            for (String wanted : labelFilter) {
                boolean found = false;
                for (String have : card.labels()) {
                    if (have.equalsIgnoreCase(wanted)) { found = true; break; }
                }
                if (!found) return false;
            }
        }
        return true;
    }

    private static Map<String, Object> toMap(KanbanFolderReader.CardFile cf,
                                             boolean includeBody) {
        CardDocument card = cf.card();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", cf.doc().getPath());
        m.put("column", cf.column());
        m.put("title", card.title());
        if (card.priority() != null) m.put("priority", card.priority());
        if (card.assignee() != null) m.put("assignee", card.assignee());
        if (!card.labels().isEmpty()) m.put("labels", new ArrayList<>(card.labels()));
        if (card.dueDate() != null) m.put("dueDate", card.dueDate());
        if (card.estimate() != null) m.put("estimate", card.estimate());
        if (card.blocked()) m.put("blocked", true);
        if (includeBody && !card.body().isEmpty()) m.put("body", card.body());
        return m;
    }

    private static Set<String> collectColumnFilter(Map<String, Object> params) {
        Set<String> out = new java.util.LinkedHashSet<>();
        String single = paramString(params, "column");
        if (single != null) out.add(sanitiseName(single));
        List<String> many = paramStringList(params, "columns");
        for (String s : many) out.add(sanitiseName(s));
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String normaliseFolder(String folder) {
        String f = folder.trim();
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        while (f.startsWith("/")) f = f.substring(1);
        if (f.isEmpty()) throw new ToolException("folder must not be empty");
        return f;
    }

    private static String sanitiseName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.length() == 0 ? "card" : sb.toString();
    }

    private static @Nullable String paramString(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static @Nullable Boolean paramBoolean(@Nullable Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s.trim());
        }
        return null;
    }

    private static List<String> paramStringList(@Nullable Map<String, Object> params, String key) {
        if (params == null) return List.of();
        Object v = params.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) out.add(s.trim());
            }
            return out;
        }
        return List.of();
    }
}
