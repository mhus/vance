package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists documents in the active project (or one passed explicitly).
 * Returns path, name, title, mimeType, size, tags — enough for Eddie
 * to surface a sentence like „du hast 12 Dokumente, davon 3 mit
 * Tag `inception`" without rummaging through full bodies.
 */
@Component
@RequiredArgsConstructor
public class DocListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of(
                            "type", "string",
                            "description", "Optional project name. Defaults "
                                    + "to the active project (project_switch)."),
                    "tag", Map.of(
                            "type", "string",
                            "description", "Optional: filter to documents "
                                    + "carrying this tag.")),
            "required", List.of());

    private final EddieContext eddieContext;
    private final DocumentService documentService;

    @Override
    public String name() {
        return "doc_list";
    }

    @Override
    public String description() {
        return "List documents in a project (active project by default). "
                + "Optional tag filter. Returns path, name, title, "
                + "mimeType, size, tags — no content.";
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
        ProjectDocument project = eddieContext.resolveProject(params, ctx, false);
        Object rawTag = params == null ? null : params.get("tag");
        String tag = rawTag instanceof String s && !s.isBlank() ? s.trim() : null;

        List<DocumentDocument> docs = tag == null
                ? documentService.listByProject(ctx.tenantId(), project.getName())
                : documentService.listByTag(ctx.tenantId(), project.getName(), tag);

        List<Map<String, Object>> rows = new ArrayList<>(docs.size());
        for (DocumentDocument d : docs) {
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
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("documents", rows);
        out.put("count", rows.size());
        return out;
    }

    @SuppressWarnings("unused")
    private static @org.jspecify.annotations.Nullable String paramString(
            Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
