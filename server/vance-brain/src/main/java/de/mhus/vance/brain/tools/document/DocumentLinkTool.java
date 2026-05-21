package de.mhus.vance.brain.tools.document;

import de.mhus.vance.brain.tools.kinds.KindToolSupport;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
public class DocumentLinkTool implements Tool {

    private final ProjectService projectService;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;

    @Override public String name() { return "document_link"; }

    @Override public String description() {
        return "Build a Markdown link to a Document in the workspace. "
                + "Resolves path, kind, project; returns the ready-to-insert "
                + "`markdownLink` string with the correct vance: URI, kind hint, "
                + "and link syntax. Never hand-construct vance: URIs — use this tool.";
    }

    @Override public boolean primary() { return true; }

    @Override public Set<String> labels() {
        return Set.of("document", "link", "read", "eddie", "arthur");
    }

    @Override public Map<String, Object> paramsSchema() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of(
                "type", "string",
                "description",
                "Document path inside a project (the document's name, e.g. "
                        + "'documents/q1/summary.pdf'). Required unless 'id' is given."));
        p.put("id", Map.of(
                "type", "string",
                "description", "Alternative: Mongo id of the document. Use one of path/id."));
        p.put("project", Map.of(
                "type", "string",
                "description",
                "Optional project name for cross-project links (same tenant). "
                        + "Default = current project."));
        p.put("text", Map.of(
                "type", "string",
                "description",
                "Link / alt text. Default: document.title, fallback to the path's "
                        + "leaf segment."));
        p.put("mode", Map.of(
                "type", "string",
                "enum", List.of("preview", "reference"),
                "description",
                "Render-mode hint. Default derived from kind: image → preview, "
                        + "all others → reference. Use 'preview' for inline-render, "
                        + "'reference' for compact card/badge."));
        p.put("imageStyle", Map.of(
                "type", "boolean",
                "description",
                "Force image-style '![alt](...)' syntax. Default: true for kind=image/svg, "
                        + "false otherwise."));
        return Map.of(
                "type", "object",
                "properties", p);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String id = KindToolSupport.paramString(params, "id");
        String path = KindToolSupport.paramString(params, "path");
        String requestedProject = KindToolSupport.paramString(params, "project");
        String textOverride = KindToolSupport.paramString(params, "text");
        String modeOverride = KindToolSupport.paramString(params, "mode");
        Boolean imageStyleOverride = KindToolSupport.paramBoolean(params, "imageStyle");

        if (id == null && path == null) {
            throw new ToolException("Provide either 'path' or 'id'.");
        }

        DocumentDocument doc = resolveDocument(id, path, requestedProject, ctx);

        DocumentLinkBuilder.Result r = linkBuilder.build(
                doc, ctx.projectId(), textOverride, modeOverride, imageStyleOverride);

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

    private DocumentDocument resolveDocument(
            @Nullable String id,
            @Nullable String path,
            @Nullable String requestedProject,
            ToolInvocationContext ctx) {
        if (id != null) {
            DocumentDocument doc = documentService.findById(id)
                    .orElseThrow(() -> new ToolException("DOCUMENT_NOT_FOUND: id='" + id + "'"));
            if (!ctx.tenantId().equals(doc.getTenantId())) {
                throw new ToolException("CROSS_TENANT_DENIED: document is not in your tenant.");
            }
            return doc;
        }
        String projectName = resolveTargetProjectName(requestedProject, ctx);
        return documentService.findByPath(ctx.tenantId(), projectName, path)
                .orElseThrow(() -> new ToolException(
                        "DOCUMENT_NOT_FOUND: path='" + path + "' in project '" + projectName + "'"));
    }

    private String resolveTargetProjectName(
            @Nullable String requestedProject, ToolInvocationContext ctx) {
        if (requestedProject == null) {
            String current = ctx.projectId();
            if (current == null || current.isBlank()) {
                throw new ToolException(
                        "No project specified and no current project in context. "
                                + "Pass `project` explicitly.");
            }
            return current;
        }
        Optional<ProjectDocument> p = projectService.findByTenantAndName(ctx.tenantId(), requestedProject);
        if (p.isEmpty()) {
            throw new ToolException(
                    "CROSS_PROJECT_NOT_IN_TENANT: project '" + requestedProject
                            + "' does not exist in this tenant.");
        }
        return p.get().getName();
    }
}
