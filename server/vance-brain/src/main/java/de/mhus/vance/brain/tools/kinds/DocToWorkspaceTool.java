package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.brain.tools.workspace.WorkspaceDirResolver;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Materialise a document's inline body as a file inside the
 * scratch — bridge between the document-pool and the
 * shell-friendly scratch world (so that subsequent tools like
 * {@code workspace_execute_javascript}, {@code git_checkout} or
 * {@code client_exec_run} can act on the content).
 */
@Component
@RequiredArgsConstructor
public class DocToWorkspaceTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("workspacePath"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>(KindToolSupport.documentSelectorProperties());
        p.put("workspacePath", Map.of("type", "string",
                "description", "Relative path inside the scratch dir, e.g. 'sources/notes.md'."));
        p.put("dirName", Map.of("type", "string",
                "description", "Optional scratch RootDir name. Default: the process's current "
                        + "working dir (same convention as `scratch_write`)."));
        return p;
    }

    private final KindToolSupport support;
    private final WorkspaceService workspace;

    @Override public String name() { return "doc_to_scratch"; }
    @Override public String description() {
        return "Write a document's inline body into the scratch as a file. The document is "
                + "untouched; only the scratch gets the copy. Pending buffered writes are "
                + "flushed first so the scratch file matches the latest in-flight content.";
    }
    @Override public boolean primary() { return false; }
    @Override public Set<String> labels() { return Set.of("scratch-bridge", "eddie", "write", "scratch"); }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        DocumentDocument doc = support.requireInline(support.loadDocument(params, ctx));
        String workspacePath = KindToolSupport.requireString(params, "workspacePath");
        String dirNameParam = KindToolSupport.paramString(params, "dirName");
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, dirNameParam);

        // Flush buffer so we write the in-flight body, not stale disk.
        support.buffer().flush(ctx.processId(), doc.getId());
        DocumentDocument fresh = support.buffer().read(ctx.processId(), doc.getId());
        if (fresh == null) throw new ToolException("Source document disappeared during export");
        String body = support.readBody(fresh, ctx);

        try {
            Path written = workspace.write(ctx.tenantId(), ctx.projectId(), dirName, workspacePath, body);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("documentId", fresh.getId());
            out.put("documentPath", fresh.getPath());
            out.put("workspacePath", workspacePath);
            out.put("dirName", dirName);
            out.put("absolutePath", written.toString());
            out.put("chars", body.length());
            return out;
        } catch (RuntimeException e) {
            throw new ToolException("Failed to write scratch file: " + e.getMessage(), e);
        }
    }
}
