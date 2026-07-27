package de.mhus.vance.brain.tools.foreign;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Read a single document from another project by {@code path} (or {@code id}).
 * Requires READ on the target project. Reserved {@code _}-prefixed paths are
 * refused — foreign config/manuals stay behind the normal cascade, not here.
 * Long bodies are truncated past {@link #MAX_BODY_CHARS}.
 */
@Component
@RequiredArgsConstructor
public class ForeignDocReadTool implements Tool {

    private static final int MAX_BODY_CHARS = 50_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "projectId", Map.of("type", "string",
                            "description", "Name of the source project (required)."),
                    "path", Map.of("type", "string",
                            "description", "Document path inside that project, e.g. 'notes/plan.md'."),
                    "id", Map.of("type", "string",
                            "description", "Alternative: Mongo id of the document (must belong to "
                                    + "the named project). Use one of path/id.")),
            "required", List.of("projectId"));

    private final ForeignAccessSupport foreign;

    @Override public String name() { return "foreign_doc_read"; }

    @Override public String description() {
        return "Read a document's text content from ANOTHER project in your tenant. Identify it by "
                + "path (preferred) or id. Read-only; requires read access to that project. Returns "
                + "title, tags, mimeType, content (truncated past " + MAX_BODY_CHARS + " chars).";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public Set<String> labels() { return Set.of("read-only", "cross-project", "document"); }
    @Override public String searchHint() { return "Read a document from another project"; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String projectId = KindToolSupport.requireString(params, "projectId");
        String path = KindToolSupport.paramString(params, "path");
        String id = KindToolSupport.paramString(params, "id");
        if (path == null && id == null) {
            throw new ToolException("Provide either 'path' or 'id'");
        }
        ProjectDocument project = foreign.resolveForeign(projectId, ctx, Action.READ);

        DocumentDocument doc;
        if (id != null) {
            doc = foreign.documents().findById(id)
                    .orElseThrow(() -> new ToolException("Document with id '" + id + "' not found"));
            if (!ctx.tenantId().equals(doc.getTenantId())
                    || !project.getName().equals(doc.getProjectId())) {
                throw new ToolException("Document with id '" + id
                        + "' is not in project '" + project.getName() + "'");
            }
        } else {
            if (ForeignAccessSupport.reserved(path)) {
                throw new ToolException("Path '" + path + "' is in a reserved namespace; "
                        + "foreign_doc_read cannot reach it");
            }
            doc = foreign.documents().findByPath(ctx.tenantId(), project.getName(), path)
                    .orElseThrow(() -> new ToolException("Document '" + path
                            + "' not found in project '" + project.getName() + "'"));
        }
        if (ForeignAccessSupport.reserved(doc.getPath())) {
            throw new ToolException("Document '" + doc.getPath()
                    + "' is in a reserved namespace; foreign_doc_read cannot reach it");
        }

        String content = foreign.readText(doc);
        int fullLength = content.length();
        boolean truncated = fullLength > MAX_BODY_CHARS;
        String body = truncated ? content.substring(0, MAX_BODY_CHARS) : content;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("projectId", doc.getProjectId());
        out.put("path", doc.getPath());
        out.put("name", doc.getName());
        if (doc.getTitle() != null) out.put("title", doc.getTitle());
        if (doc.getMimeType() != null) out.put("mimeType", doc.getMimeType());
        if (doc.getTags() != null && !doc.getTags().isEmpty()) out.put("tags", doc.getTags());
        out.put("contentLength", fullLength);
        out.put("truncated", truncated);
        out.put("content", body);
        return out;
    }
}
