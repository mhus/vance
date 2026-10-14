package de.mhus.vance.brain.tools.document;

import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Build a Markdown link to a Document in the workspace — resolves
 * path, kind, project; applies link-vs-image syntax automatically.
 *
 * <p>Engines must never hand-construct {@code vance:} URIs — this
 * tool (and the shared {@link DocumentLinkBuilder}) is the canonical
 * builder. See specification/inline-and-embedded-content.md §3.1
 * and §10.1.
 */
@Component
@RequiredArgsConstructor
public class DocLinkTool implements Tool {

    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final EddieContext eddieContext;

    @Override
    public String name() {
        return "doc_link";
    }

    @Override
    public String description() {
        return "Build a Markdown link to a Document in the workspace. "
                + "Resolves path, kind, project; returns the ready-to-insert "
                + "`markdownLink` string with the correct vance: URI, kind hint, "
                + "and link syntax. Never hand-construct vance: URIs — use this tool.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, "document", "link", "read-only", "arthur");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(
                "path",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Document path inside a project (the document's name, e.g. "
                                + "'documents/q1/summary.pdf'). Required unless 'id' is given."));
        p.put(
                "id",
                Map.of(
                        "type", "string",
                        "description", "Alternative: Mongo id of the document. Use one of path/id."));
        p.put(
                "projectId",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional project name for cross-project links (same tenant). "
                                + "Default = current project."));
        p.put(
                "text",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Link / alt text. Default: document.title, fallback to the path's " + "leaf segment."));
        p.put(
                "mode",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("preview", "reference"),
                        "description",
                        "Render-mode hint. Default derived from kind: image → preview, "
                                + "all others → reference. Use 'preview' for inline-render, "
                                + "'reference' for compact card/badge."));
        p.put(
                "imageStyle",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "Force image-style '![alt](...)' syntax. Default: true for kind=image/svg, "
                                + "false otherwise."));
        return Map.of("type", "object", "properties", p);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String id = KindToolSupport.paramString(params, "id");
        String path = KindToolSupport.paramString(params, "path");
        // 'projectId' is what every other doc_* tool calls this; 'project' is
        // the name this one shipped with and stays readable so existing
        // prompts and saved calls keep working. Without the alias the old
        // spelling would silently resolve to the current project — a wrong
        // link with no error, which is how this was found.
        String requestedProject = KindToolSupport.paramString(params, "projectId");
        if (requestedProject == null) {
            requestedProject = KindToolSupport.paramString(params, "project");
        }
        String textOverride = KindToolSupport.paramString(params, "text");
        String modeOverride = KindToolSupport.paramString(params, "mode");
        Boolean imageStyleOverride = KindToolSupport.paramBoolean(params, "imageStyle");

        if (id == null && path == null) {
            throw new ToolException("Provide either 'path' or 'id'.");
        }

        DocumentDocument doc = resolveDocument(id, path, requestedProject, ctx);

        DocumentLinkBuilder.Result r =
                linkBuilder.build(doc, ctx.projectId(), textOverride, modeOverride, imageStyleOverride);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("markdownLink", r.markdownLink());
        out.put("path", r.path());
        out.put("kind", r.kind());
        out.put("project", r.project());
        out.put("title", r.title());
        out.put("mode", r.mode());
        out.put("imageStyle", r.imageStyle());
        return out;
    }

    /**
     * The link carries the document's title and path, so building one is a
     * read: both branches pass the document tools' read gate (tenant, READ on
     * the document, engine confinement). The path branch resolves its project
     * through {@link EddieContext#resolveProject} like every other doc tool —
     * Project READ and confinement are checked <em>before</em> the path is
     * looked up, so a refusal and a missing document cannot be told apart in
     * a project the caller may not read.
     */
    private DocumentDocument resolveDocument(
            @Nullable String id, @Nullable String path, @Nullable String requestedProject, ToolInvocationContext ctx) {
        DocumentDocument doc = loadDocument(id, path, requestedProject, ctx);
        eddieContext.requireReadableById(ctx, doc, doc.getId() == null ? String.valueOf(path) : doc.getId());
        return doc;
    }

    private DocumentDocument loadDocument(
            @Nullable String id, @Nullable String path, @Nullable String requestedProject, ToolInvocationContext ctx) {
        if (id != null) {
            return documentService
                    .findById(id)
                    .filter(doc -> ctx.tenantId().equals(doc.getTenantId()))
                    .orElseThrow(() -> new ToolException("DOCUMENT_NOT_FOUND: id='" + id + "'"));
        }
        Map<String, Object> projectParams = requestedProject == null ? Map.of() : Map.of("projectId", requestedProject);
        ProjectDocument project = eddieContext.resolveProject(projectParams, ctx, false);
        String projectName = project.getName();
        return documentService
                .findByPath(ctx.tenantId(), projectName, path)
                .orElseThrow(() ->
                        new ToolException("DOCUMENT_NOT_FOUND: path='" + path + "' in project '" + projectName + "'"));
    }
}
