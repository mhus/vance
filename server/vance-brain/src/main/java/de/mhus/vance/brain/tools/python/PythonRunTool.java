package de.mhus.vance.brain.tools.python;

import de.mhus.vance.brain.tools.exec.ExecLabels;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.exec.ExecProperties;
import de.mhus.vance.brain.tools.exec.SubmitOptions;
import de.mhus.vance.brain.tools.workspace.WorkspaceDirResolver;
import de.mhus.vance.shared.workspace.PythonHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Runs a Python file inside the named Python RootDir's venv. The
 * working directory is the RootDir, so relative imports and file
 * accesses resolve as expected. Long-running scripts come back still
 * RUNNING with a job id; the LLM follows up via {@code exec_status}.
 */
@Component
@RequiredArgsConstructor
public class PythonRunTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "file", Map.of(
                            "type", "string",
                            "description",
                                    "Python file to execute, path relative to the "
                                            + "RootDir. Required."),
                    "args", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                                    "Arguments passed to the script. Each item is "
                                            + "shell-escaped automatically."),
                    "flags", Map.of(
                            "type", "string",
                            "description",
                                    "Python interpreter flags (e.g. '-O', '-X dev'). "
                                            + "Appended verbatim before the file path."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional Python RootDir. Defaults to the "
                                            + "current process's working RootDir."),
                    "waitMs", Map.of(
                            "type", "integer",
                            "description", "Milliseconds to wait before returning early.")),
            "required", List.of("file"));

    private final WorkspaceService workspaceService;
    private final ExecManager execManager;
    private final ExecProperties properties;

    @Override
    public String name() {
        return "python_run";
    }

    @Override
    public String description() {
        return "Run a Python file with the named Python RootDir's venv "
                + "interpreter. Working directory is the RootDir. Returns "
                + "the same shape as exec_run; long-running scripts come "
                + "back with status=RUNNING and a job id for exec_status.";
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
    public Set<String> labels() {
        return Set.of("executive", "side-effect");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Run a Python file in a Python RootDir venv";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String file = requireString(params, "file");
        String flags = stringOrNull(params, "flags");
        List<String> args = stringList(params, "args");
        long waitMs = waitMs(params, properties.getDefaultWaitMs());

        String dirName = WorkspaceDirResolver.resolve(
                workspaceService, ctx, stringOrNull(params, "dirName"));
        ensurePythonType(ctx, dirName);

        StringBuilder cmd = new StringBuilder(".venv/bin/python");
        if (StringUtils.isNotBlank(flags)) {
            cmd.append(' ').append(flags);
        }
        cmd.append(' ').append(PythonShellEscape.quote(file));
        for (String arg : args) {
            cmd.append(' ').append(PythonShellEscape.quote(arg));
        }

        Map<String, String> labels = Map.of(
                ExecLabels.KEY_SOURCE, ExecLabels.SOURCE_LLM_TOOL,
                ExecLabels.KEY_LANGUAGE, ExecLabels.LANG_PYTHON,
                ExecLabels.KEY_RUN_KIND, ExecLabels.RUN_KIND_SCRIPT,
                ExecLabels.KEY_DOCUMENT, file);
        try {
            return execManager.submitTrackedAndRender(
                    ctx.tenantId(), ctx.projectId(),
                    ctx.sessionId(), ctx.processId(),
                    dirName, cmd.toString(), waitMs,
                    SubmitOptions.defaults().withLabels(labels));
        } catch (RuntimeException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private void ensurePythonType(ToolInvocationContext ctx, String dirName) {
        RootDirHandle handle = workspaceService.getRootDir(ctx.tenantId(), ctx.projectId(), dirName)
                .orElseThrow(() -> new ToolException(
                        "Unknown RootDir: " + dirName));
        if (!PythonHandler.TYPE.equals(handle.getType())) {
            throw new ToolException("python_run refused: RootDir '" + dirName
                    + "' has type '" + handle.getType() + "', expected '"
                    + PythonHandler.TYPE + "'");
        }
    }

    private static String requireString(Map<String, Object> params, String key) {
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

    private static List<String> stringList(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(o -> o instanceof String)
                .map(o -> (String) o)
                .toList();
    }

    private static long waitMs(Map<String, Object> params, long fallback) {
        Object raw = params == null ? null : params.get("waitMs");
        return raw instanceof Number n && n.longValue() >= 0 ? n.longValue() : fallback;
    }
}
