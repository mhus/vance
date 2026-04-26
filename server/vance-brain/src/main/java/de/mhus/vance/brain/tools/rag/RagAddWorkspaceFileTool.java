package de.mhus.vance.brain.tools.rag;

import de.mhus.vance.brain.rag.RagService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.tools.workspace.WorkspaceException;
import de.mhus.vance.brain.tools.workspace.WorkspaceService;
import de.mhus.vance.shared.rag.RagDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a file from the project workspace and ingests its content
 * into a RAG. Convenience composition over {@code workspace_read} +
 * {@code rag_add_text}. {@code sourceRef} defaults to the file's
 * relative path so re-ingesting the same file replaces its prior
 * chunks instead of duplicating.
 */
@Component
@RequiredArgsConstructor
public class RagAddWorkspaceFileTool implements Tool {

    /** Soft cap matching the workspace read default — beyond this we let the LLM page. */
    private static final int READ_CHAR_CAP = 0; // 0 = unlimited; the RAG itself caps via chunking

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "RAG name within the current project."),
                    "path", Map.of(
                            "type", "string",
                            "description", "Relative path to a workspace file.")),
            "required", List.of("name", "path"));

    private final RagService ragService;
    private final WorkspaceService workspaceService;

    @Override
    public String name() {
        return "rag_add_workspace_file";
    }

    @Override
    public String description() {
        return "Read a project workspace file and ingest it into a RAG. "
                + "sourceRef is the relative path; re-running on the same "
                + "file replaces its prior chunks.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String projectId = ctx.projectId();
        if (projectId == null) {
            throw new ToolException("RAG tools require a project scope");
        }
        String name = stringOrThrow(params, "name");
        String path = stringOrThrow(params, "path");

        RagDocument rag = ragService.findByName(ctx.tenantId(), projectId, name)
                .orElseThrow(() -> new ToolException("Unknown RAG '" + name
                        + "' in project '" + projectId + "'"));

        WorkspaceService.ReadResult read;
        try {
            read = workspaceService.read(projectId, path, READ_CHAR_CAP);
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
        try {
            long replaced = ragService.removeBySource(rag.getId(), path);
            RagService.IngestResult result = ragService.addText(
                    rag.getId(), path, read.text(), null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rag", rag.getName());
            out.put("path", path);
            out.put("sourceRef", path);
            out.put("chunksAdded", result.chunksAdded());
            if (replaced > 0) out.put("chunksReplaced", replaced);
            out.put("totalChars", read.totalChars());
            return out;
        } catch (RuntimeException e) {
            throw new ToolException("rag_add_workspace_file failed: " + e.getMessage(), e);
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
