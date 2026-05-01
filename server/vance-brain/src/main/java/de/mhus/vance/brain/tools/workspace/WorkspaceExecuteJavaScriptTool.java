package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.brain.tools.js.JsEngine;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a {@code .js} file from a project workspace RootDir and
 * evaluates it with {@link JsEngine}. Pairs with {@code workspace_write}
 * so the LLM can iteratively develop and re-run scripts. When {@code
 * dirName} is omitted, the per-process temp RootDir is used.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceExecuteJavaScriptTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of(
                            "type", "string",
                            "description",
                                    "Relative path to a .js file inside the RootDir."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional RootDir name. Defaults to the "
                                            + "current process's temp RootDir.")),
            "required", List.of("path"));

    private final WorkspaceService workspace;
    private final JsEngine jsEngine;

    @Override
    public String name() {
        return "execute_workspace_javascript";
    }

    @Override
    public String description() {
        return "Execute a JavaScript file previously written to a project "
                + "workspace RootDir. Returns the value of the last "
                + "expression as a string.";
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
            Path file = workspace.readablePath(ctx.projectId(), dirName, path);
            String code = Files.readString(file, StandardCharsets.UTF_8);
            String result = jsEngine.eval(code);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("result", result);
            out.put("engine", jsEngine.mode().name().toLowerCase());
            return out;
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        } catch (Exception e) {
            throw new ToolException("Execute failed: " + e.getMessage(), e);
        }
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
