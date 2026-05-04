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

/**
 * List documents in a project carrying a specific tag — uses
 * {@link DocumentService#listByTag} under the hood. Trash entries
 * are filtered out.
 */
@Component
@RequiredArgsConstructor
public class DocListByTagTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Optional project name. Defaults to the active project."),
                    "tag", Map.of("type", "string", "description", "Tag to filter by."),
                    "pathPrefix", Map.of("type", "string",
                            "description", "Optional path-prefix filter on top of the tag.")),
            "required", List.of("tag"));

    private final KindToolSupport support;

    @Override public String name() { return "doc_list_by_tag"; }
    @Override public String description() {
        return "List documents in the project that carry the given tag. Optional `pathPrefix` "
                + "narrows the result. Trashed documents are excluded.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("tags", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        String tag = KindToolSupport.requireString(params, "tag");
        String pathPrefix = KindToolSupport.paramString(params, "pathPrefix");
        List<DocumentDocument> hits = support.documentService()
                .listByTag(ctx.tenantId(), project.getName(), tag);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (DocumentDocument d : hits) {
            if (DocumentService.isTrash(d.getPath())) continue;
            if (pathPrefix != null && !d.getPath().startsWith(pathPrefix)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", d.getId());
            entry.put("path", d.getPath());
            if (d.getTitle() != null) entry.put("title", d.getTitle());
            if (d.getKind() != null) entry.put("kind", d.getKind());
            if (d.getMimeType() != null) entry.put("mimeType", d.getMimeType());
            entry.put("size", d.getSize());
            entry.put("tags", d.getTags());
            entries.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", project.getName());
        out.put("tag", tag);
        out.put("count", entries.size());
        out.put("entries", entries);
        return out;
    }
}
