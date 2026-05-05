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
 * Lists files inside a project workspace RootDir (recursive). Returns
 * relative paths, sorted. Directories are not included. When {@code
 * dirName} is omitted, the per-process temp RootDir is used.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional RootDir name. Defaults to the "
                                            + "current process's temp RootDir.")),
            "required", List.of());

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "workspace_list";
    }

    @Override
    public String description() {
        return "List files in a project workspace RootDir (recursive). "
                + "Returns relative paths.";
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
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        try {
            List<String> files = workspace.list(ctx.tenantId(), ctx.projectId(), dirName);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dirName", dirName);
            out.put("files", files);
            out.put("count", files.size());
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
