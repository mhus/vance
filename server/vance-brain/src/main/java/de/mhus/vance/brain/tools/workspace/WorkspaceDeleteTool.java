package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deletes a file from a project workspace RootDir. Returns {@code
 * deleted: false} instead of failing if the file isn't there. When
 * {@code dirName} is omitted, the per-process temp RootDir is used.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description", "Relative path inside the RootDir."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional RootDir name. Defaults to the "
                                            + "current process's temp RootDir.")),
            "required", List.of("path"));

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "workspace_delete";
    }

    @Override
    public String description() {
        return "Delete a file from a project workspace RootDir. Safe to call "
                + "on a path that doesn't exist — returns deleted=false.";
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
        Object raw = params == null ? null : params.get("path");
        if (!(raw instanceof String path) || path.isBlank()) {
            throw new ToolException("'path' is required and must be a non-empty string");
        }
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        try {
            boolean deleted = workspace.delete(ctx.tenantId(), ctx.projectId(), dirName, path);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("deleted", deleted);
            return out;
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
