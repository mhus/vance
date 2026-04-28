package de.mhus.vance.brain.tools.vance;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Substring-match document search across path, name, title and tags.
 * Returns the same metadata shape as {@code doc_list} so Vance can
 * pipe a hit straight into {@code doc_read(id=...)}.
 *
 * <p>For semantic / RAG-style search, the project's RAG namespaces
 * exist separately ({@code rag_query}). This tool is the cheap
 * "I know roughly what it's called" entry-point.
 */
@Component
@RequiredArgsConstructor
public class DocFindTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name. Defaults "
                                    + "to the active project."),
                    "query", Map.of(
                            "type", "string",
                            "description", "Substring to match against "
                                    + "path, name, title, or tag (case-insensitive)."),
                    "limit", Map.of(
                            "type", "integer",
                            "description", "Max number of hits "
                                    + "(default " + DEFAULT_LIMIT
                                    + ", max " + MAX_LIMIT + ").")),
            "required", List.of("query"));

    private final VanceContext vanceContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "doc_find";
    }

    @Override
    public String description() {
        return "Find documents in a project by case-insensitive "
                + "substring match against path / name / title / tags. "
                + "Use this when you know roughly what the doc is called. "
                + "For semantic search use rag_query.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object rawQuery = params == null ? null : params.get("query");
        if (!(rawQuery instanceof String query) || query.isBlank()) {
            throw new ToolException("'query' is required");
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        int limit = clampLimit(params == null ? null : params.get("limit"));

        ProjectDocument project = vanceContext.resolveProject(params, ctx, false);
        List<DocumentDocument> all =
                documentService.listByProject(ctx.tenantId(), project.getName());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DocumentDocument d : all) {
            if (matches(d, needle)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", d.getId());
                row.put("path", d.getPath());
                row.put("name", d.getName());
                if (d.getTitle() != null) row.put("title", d.getTitle());
                if (d.getMimeType() != null) row.put("mimeType", d.getMimeType());
                row.put("size", d.getSize());
                if (d.getTags() != null && !d.getTags().isEmpty()) {
                    row.put("tags", d.getTags());
                }
                rows.add(row);
                if (rows.size() >= limit) break;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("query", query);
        out.put("hits", rows);
        out.put("count", rows.size());
        out.put("truncated", rows.size() >= limit);
        return out;
    }

    private static boolean matches(DocumentDocument d, String needle) {
        if (containsCi(d.getPath(), needle)) return true;
        if (containsCi(d.getName(), needle)) return true;
        if (containsCi(d.getTitle(), needle)) return true;
        if (d.getTags() != null) {
            for (String tag : d.getTags()) {
                if (containsCi(tag, needle)) return true;
            }
        }
        return false;
    }

    private static boolean containsCi(@org.jspecify.annotations.Nullable String haystack, String needleLc) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLc);
    }

    private static int clampLimit(@org.jspecify.annotations.Nullable Object raw) {
        int n = DEFAULT_LIMIT;
        if (raw instanceof Number number) n = number.intValue();
        else if (raw instanceof String s) {
            try { n = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        if (n < 1) return 1;
        return Math.min(n, MAX_LIMIT);
    }
}
