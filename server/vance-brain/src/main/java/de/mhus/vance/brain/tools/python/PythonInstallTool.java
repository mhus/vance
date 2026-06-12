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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Installs one or more Python packages via {@code pip} into the named
 * Python RootDir's venv and refreshes {@code requirements.txt} with
 * {@code pip freeze}. Accepts either a single {@code package} (string)
 * or a {@code packages} (array of strings) — multi-package mode runs
 * one {@code pip install pkg1 pkg2 …} so the LLM doesn't have to
 * chain N tool calls. Optional {@code flags} string is appended
 * verbatim to {@code pip install} for power-user switches
 * ({@code --upgrade}, {@code --no-deps}, {@code --index-url …}).
 */
@Component
@RequiredArgsConstructor
public class PythonInstallTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "package", Map.of(
                            "type", "string",
                            "description",
                                    "Single package spec for pip (e.g. 'requests', "
                                            + "'flask==3.0', 'numpy>=2'). Provide this "
                                            + "OR 'packages'."),
                    "packages", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                                    "Multiple package specs installed in one pip call. "
                                            + "Faster than chaining python_install. "
                                            + "Provide this OR 'package'."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional Python RootDir name. Defaults to the "
                                            + "current process's working RootDir."),
                    "flags", Map.of(
                            "type", "string",
                            "description",
                                    "Extra arguments appended verbatim to 'pip install' "
                                            + "(e.g. '--upgrade --no-deps'). Optional."),
                    "waitMs", Map.of(
                            "type", "integer",
                            "description", "Milliseconds to wait before returning early.")),
            "required", List.of());

    private final WorkspaceService workspaceService;
    private final ExecManager execManager;
    private final ExecProperties properties;

    @Override
    public String name() {
        return "python_install";
    }

    @Override
    public String description() {
        return "Install one or more Python packages via pip into the named "
                + "Python RootDir's venv. Pass 'package' for a single spec "
                + "or 'packages' for a batch — one pip call either way. "
                + "Refreshes requirements.txt with pip freeze afterwards so "
                + "suspend captures the new pins.";
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
        return "pip install into Python RootDir venv";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        List<String> pkgs = collectPackages(params);
        String flags = stringOrNull(params, "flags");
        long waitMs = waitMs(params, properties.getDefaultWaitMs());

        String dirName = WorkspaceDirResolver.resolve(
                workspaceService, ctx, stringOrNull(params, "dirName"));
        ensurePythonType(ctx, dirName);

        StringBuilder pipInstall = new StringBuilder(".venv/bin/python -m pip install");
        for (String pkg : pkgs) {
            pipInstall.append(' ').append(PythonShellEscape.quote(pkg));
        }
        if (StringUtils.isNotBlank(flags)) {
            pipInstall.append(' ').append(flags);
        }
        String command = pipInstall
                + " && .venv/bin/python -m pip freeze > " + PythonHandler.REQUIREMENTS_FILE;

        Map<String, String> labels = Map.of(
                ExecLabels.KEY_SOURCE, ExecLabels.SOURCE_LLM_TOOL,
                ExecLabels.KEY_LANGUAGE, ExecLabels.LANG_PYTHON,
                ExecLabels.KEY_RUN_KIND, ExecLabels.RUN_KIND_INSTALL);
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

    private static List<String> collectPackages(Map<String, Object> params) {
        List<String> out = new ArrayList<>();
        String single = stringOrNull(params, "package");
        if (single != null) {
            out.add(single);
        }
        Object rawList = params == null ? null : params.get("packages");
        if (rawList instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
        if (out.isEmpty()) {
            throw new ToolException(
                    "python_install needs 'package' (string) or "
                            + "'packages' (non-empty list of strings)");
        }
        return out;
    }

    private void ensurePythonType(ToolInvocationContext ctx, String dirName) {
        RootDirHandle handle = workspaceService.getRootDir(ctx.tenantId(), ctx.projectId(), dirName)
                .orElseThrow(() -> new ToolException(
                        "Unknown RootDir: " + dirName));
        if (!PythonHandler.TYPE.equals(handle.getType())) {
            throw new ToolException("python_install refused: RootDir '" + dirName
                    + "' has type '" + handle.getType() + "', expected '"
                    + PythonHandler.TYPE + "'");
        }
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
