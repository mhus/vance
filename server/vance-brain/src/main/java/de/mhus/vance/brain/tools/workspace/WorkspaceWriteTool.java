package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Creates or overwrites a UTF-8 text file in a project workspace
 * RootDir. Use it for fresh files and complete rewrites — it always
 * replaces the whole content. When {@code dirName} is omitted, the
 * per-process temp RootDir is used.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceWriteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description",
                                    "Relative path inside the RootDir, "
                                            + "e.g. 'tool.js' or 'dir/tool.js'."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional RootDir name. Defaults to the "
                                            + "current process's temp RootDir."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Full file content. Replaces any existing content.")),
            "required", List.of("path", "content"));

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "workspace_write";
    }

    @Override
    public String description() {
        return "Create or overwrite a text file in a project workspace "
                + "RootDir. Use relative paths; parent directories are "
                + "created automatically.";
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
        String path = stringOrThrow(params, "path");
        String content = params == null ? null : (String) params.get("content");
        if (content == null) {
            throw new ToolException("'content' is required");
        }
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        try {
            Path written = workspace.write(ctx.projectId(), dirName, path, content);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("absolutePath", written.toString());
            out.put("chars", content.length());
            return out;
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
