package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists all files currently in the session workspace (recursive).
 * Returns relative paths, sorted. Directories are not included.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "workspace_list";
    }

    @Override
    public String description() {
        return "List files in the session workspace (recursive). Returns "
                + "relative paths.";
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
        try {
            List<String> files = workspace.list(ctx.sessionId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("files", files);
            out.put("count", files.size());
            return out;
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }
}
