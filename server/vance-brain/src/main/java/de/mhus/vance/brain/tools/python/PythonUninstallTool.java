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
 * Removes a Python package via {@code pip uninstall} from the named
 * Python RootDir's venv and refreshes {@code requirements.txt} with
 * {@code pip freeze}. Always passes {@code -y} to skip confirmation
 * prompts (pip is non-interactive in this context).
 */
@Component
@RequiredArgsConstructor
public class PythonUninstallTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "package", Map.of(
                            "type", "string",
                            "description", "Package name to uninstall. Required."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional Python RootDir name. Defaults to the "
                                            + "current process's working RootDir."),
                    "flags", Map.of(
                            "type", "string",
                            "description",
                                    "Extra arguments appended verbatim to "
                                            + "'pip uninstall -y'. Optional."),
                    "waitMs", Map.of(
                            "type", "integer",
                            "description", "Milliseconds to wait before returning early.")),
            "required", List.of("package"));

    private final WorkspaceService workspaceService;
    private final ExecManager execManager;
    private final ExecProperties properties;

    @Override
    public String name() {
        return "python_uninstall";
    }

    @Override
    public String description() {
        return "Uninstall a Python package from the named Python RootDir's "
                + "venv (pip uninstall -y). Refreshes requirements.txt afterwards.";
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
        return Set.of("write", "side-effect");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "pip uninstall from Python RootDir venv";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String pkg = requireString(params, "package");
        String flags = stringOrNull(params, "flags");
        long waitMs = waitMs(params, properties.getDefaultWaitMs());

        String dirName = WorkspaceDirResolver.resolve(
                workspaceService, ctx, stringOrNull(params, "dirName"));
        ensurePythonType(ctx, dirName);

        String pipUninstall = ".venv/bin/python -m pip uninstall -y "
                + PythonShellEscape.quote(pkg);
        if (StringUtils.isNotBlank(flags)) {
            pipUninstall += " " + flags;
        }
        String command = pipUninstall
                + " && .venv/bin/python -m pip freeze > " + PythonHandler.REQUIREMENTS_FILE;

        Map<String, String> labels = Map.of(
                ExecLabels.KEY_SOURCE, ExecLabels.SOURCE_LLM_TOOL,
                ExecLabels.KEY_LANGUAGE, ExecLabels.LANG_PYTHON,
                ExecLabels.KEY_RUN_KIND, ExecLabels.RUN_KIND_UNINSTALL);
        try {
            return execManager.submitTrackedAndRender(
                    ctx.tenantId(), ctx.projectId(),
                    ctx.sessionId(), ctx.processId(),
                    dirName, command, waitMs,
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
            throw new ToolException("python_uninstall refused: RootDir '" + dirName
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

    private static long waitMs(Map<String, Object> params, long fallback) {
        Object raw = params == null ? null : params.get("waitMs");
        return raw instanceof Number n && n.longValue() >= 0 ? n.longValue() : fallback;
    }
}
