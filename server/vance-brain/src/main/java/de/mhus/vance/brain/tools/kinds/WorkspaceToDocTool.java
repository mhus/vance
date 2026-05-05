package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.tools.workspace.WorkspaceDirResolver;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Read a workspace file and turn it into a project document — the
 * other half of the {@code doc_to_workspace} bridge.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code path} not yet present in the project → creates a new
 *       inline document.</li>
 *   <li>{@code path} already exists → updates the existing document's
 *       inline body (id stays the same).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WorkspaceToDocTool implements Tool {

    private static final int MAX_IMPORT_CHARS = 200_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("workspacePath", "documentPath"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("projectId", Map.of("type", "string",
                "description", "Optional project name. Defaults to the active project."));
        p.put("workspacePath", Map.of("type", "string",
                "description", "Relative path inside the workspace dir to read."));
        p.put("dirName", Map.of("type", "string",
                "description", "Optional workspace RootDir name."));
        p.put("documentPath", Map.of("type", "string",
                "description", "Target path inside the project (e.g. 'imported/notes.md')."));
        p.put("title", Map.of("type", "string", "description", "Optional title for new docs."));
        p.put("mimeType", Map.of("type", "string",
                "description", "Optional mime type override. Defaults to 'text/markdown'."));
        return p;
    }

    private final KindToolSupport support;
    private final WorkspaceService workspace;

    @Override public String name() { return "workspace_to_doc"; }
    @Override public String description() {
        return "Import a workspace file into the project's document pool. Creates the document "
                + "when `documentPath` is unused, updates the existing one when it's already there. "
                + "Capped at " + MAX_IMPORT_CHARS + " characters; bigger files should stay in the "
                + "workspace.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("workspace-bridge", "eddie"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ProjectDocument project = support.eddieContext().resolveProject(params, ctx, false);
        String workspacePath = KindToolSupport.requireString(params, "workspacePath");
        String documentPath = KindToolSupport.requireString(params, "documentPath");
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx,
                KindToolSupport.paramString(params, "dirName"));
        String title = KindToolSupport.paramString(params, "title");
        String mime = KindToolSupport.paramString(params, "mimeType");
        if (mime == null) mime = "text/markdown";

        WorkspaceService.ReadResult read;
        try {
            read = workspace.read(ctx.tenantId(), ctx.projectId(), dirName, workspacePath, MAX_IMPORT_CHARS);
        } catch (RuntimeException e) {
            throw new ToolException("Cannot read workspace file: " + e.getMessage(), e);
        }
        if (read.truncated()) {
            throw new ToolException("Workspace file '" + workspacePath + "' exceeds the import "
                    + "limit (" + MAX_IMPORT_CHARS + " chars). Trim it first or keep it in the "
                    + "workspace.");
        }
        String content = read.text();

        DocumentDocument existing = support.documentService()
                .findByPath(ctx.tenantId(), project.getName(), documentPath)
                .orElse(null);

        DocumentDocument result;
        boolean created;
        if (existing == null) {
            try {
                result = support.documentService().create(
                        ctx.tenantId(),
                        project.getName(),
                        documentPath,
                        title,
                        null,
                        mime,
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                        ctx.userId());
                created = true;
            } catch (DocumentService.DocumentAlreadyExistsException e) {
                throw new ToolException(e.getMessage(), e);
            }
        } else {
            // Drop any in-flight buffer entry on the existing doc so
            // our update doesn't immediately get clobbered by a
            // pending older write.
            support.buffer().flush(ctx.processId(), existing.getId());
            result = support.documentService().update(
                    existing.getId(), title, null, content, null);
            created = false;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentId", result.getId());
        out.put("documentPath", result.getPath());
        out.put("workspacePath", workspacePath);
        out.put("dirName", dirName);
        out.put("created", created);
        out.put("chars", content.length());
        return out;
    }
}
