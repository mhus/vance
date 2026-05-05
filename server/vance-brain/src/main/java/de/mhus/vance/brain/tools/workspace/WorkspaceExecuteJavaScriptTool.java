package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a {@code .js} file from a project workspace RootDir and
 * evaluates it with {@link ScriptExecutor#runFile}. Pairs with
 * {@code workspace_write} so the LLM can iteratively develop and re-run
 * scripts. When {@code dirName} is omitted, the per-process temp RootDir
 * is used.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceExecuteJavaScriptTool implements Tool {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);

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
                                            + "current process's temp RootDir."),
                    "timeoutMs", Map.of(
                            "type", "integer",
                            "description",
                                    "Wall-clock timeout in milliseconds (default 10000, max 60000).")),
            "required", List.of("path"));

    private final WorkspaceService workspace;
    private final ScriptExecutor scriptExecutor;

    @Override
    public String name() {
        return "execute_workspace_javascript";
    }

    @Override
    public String description() {
        return "Execute a JavaScript file previously written to a project "
                + "workspace RootDir. The script sees the 'vance' host "
                + "binding (vance.tools.call, vance.context, vance.log). "
                + "Returns the value of the last expression.";
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
        throw new ToolException(
                "execute_workspace_javascript requires the bound tools surface — "
                        + "call via the engine's ContextToolsApi");
    }

    @Override
    public Map<String, Object> invoke(
            Map<String, Object> params, ToolInvocationContext ctx, ContextToolsApi tools) {
        Object raw = params == null ? null : params.get("path");
        if (!(raw instanceof String path) || path.isBlank()) {
            throw new ToolException("'path' is required and must be a non-empty string");
        }
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        Duration timeout = resolveTimeout(params);
        try {
            Path file = workspace.readablePath(ctx.projectId(), dirName, path);
            ScriptResult result = scriptExecutor.runFile(file, tools, timeout);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("value", result.value());
            out.put("durationMs", result.duration().toMillis());
            return out;
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        } catch (ScriptExecutionException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("error", e.errorClass().name());
            out.put("message", e.getMessage());
            return out;
        }
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static Duration resolveTimeout(Map<String, Object> params) {
        if (params == null) {
            return DEFAULT_TIMEOUT;
        }
        Object raw = params.get("timeoutMs");
        if (raw instanceof Number n) {
            long ms = Math.max(1, Math.min(MAX_TIMEOUT.toMillis(), n.longValue()));
            return Duration.ofMillis(ms);
        }
        return DEFAULT_TIMEOUT;
    }
}
