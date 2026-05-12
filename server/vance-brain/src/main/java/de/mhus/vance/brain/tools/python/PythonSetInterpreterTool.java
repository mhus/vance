package de.mhus.vance.brain.tools.python;

import de.mhus.vance.brain.tools.workspace.WorkspaceDirResolver;
import de.mhus.vance.shared.workspace.PythonHandler;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the venv of a Python RootDir with a different interpreter
 * binary and reinstalls from {@code requirements.txt}. Source files
 * are untouched. Synchronous — blocks until the new venv is ready.
 */
@Component
@RequiredArgsConstructor
public class PythonSetInterpreterTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "pythonPath", Map.of(
                            "type", "string",
                            "description",
                                    "Path to the new Python interpreter (e.g. "
                                            + "'/usr/bin/python3.12'). Required."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional Python RootDir name. Defaults to "
                                            + "the current process's working RootDir.")),
            "required", List.of("pythonPath"));

    private final WorkspaceService workspaceService;

    @Override
    public String name() {
        return "python_set_interpreter";
    }

    @Override
    public String description() {
        return "Switch the Python interpreter of a Python RootDir. Wipes "
                + ".venv, recreates it with the new interpreter, and "
                + "reinstalls from requirements.txt if present.";
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
        return "Switch interpreter of a Python RootDir venv";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String pythonPath = requireString(params, "pythonPath");
        String dirName = WorkspaceDirResolver.resolve(
                workspaceService, ctx, stringOrNull(params, "dirName"));

        try {
            workspaceService.rebuildPythonVenv(
                    ctx.tenantId(), ctx.projectId(), dirName, pythonPath);
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", dirName);
        out.put("pythonPath", pythonPath);
        out.put("status", "rebuilt");
        return out;
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
}
