package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** List every document currently in the project's trash folder. */
@Component
@RequiredArgsConstructor
public class DocListTrashTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Optional project name. Defaults to the active project.")),
            "required", List.of());

    private final KindToolSupport support;

    @Override public String name() { return "doc_list_trash"; }
    @Override public String description() {
        return "List every document currently in the project's trash folder (`_vance/bin/`). "
                + "Each entry includes the trashed path, original path (if recorded) and the id "
                + "for restore / purge.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("doc-management", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        List<DocumentDocument> all = support.documentService()
                .listByProject(ctx.tenantId(), project.getName());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (DocumentDocument d : all) {
            if (!DocumentService.isTrash(d.getPath())) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", d.getId());
            entry.put("trashPath", d.getPath());
            String original = d.getHeaders() != null
                    ? d.getHeaders().get(DocumentService.TRASH_ORIGINAL_PATH_HEADER)
                    : null;
            if (original != null) entry.put("originalPath", original);
            if (d.getMimeType() != null) entry.put("mimeType", d.getMimeType());
            if (d.getKind() != null) entry.put("kind", d.getKind());
            entry.put("size", d.getSize());
            entries.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("count", entries.size());
        out.put("entries", entries);
        return out;
    }
}
