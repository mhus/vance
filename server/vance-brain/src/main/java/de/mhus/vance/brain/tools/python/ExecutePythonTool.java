package de.mhus.vance.brain.tools.python;

import de.mhus.vance.brain.tools.exec.ExecLabels;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.exec.ExecProperties;
import de.mhus.vance.brain.tools.exec.SubmitOptions;
import de.mhus.vance.shared.workspace.PythonHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * One-shot Python execution — the JavaScript analog
 * ({@link de.mhus.vance.brain.tools.js.JavaScriptTool}) for Python. Pass
 * the script source as {@code code}; the tool ensures a default Python
 * RootDir ({@code _python}) exists (creating it with a fresh venv on
 * first call, idempotent thereafter), writes the source to a transient
 * file inside that RootDir, and runs it through the same
 * {@link ExecManager} pathway as {@code python_run}.
 *
 * <p>Sits next to {@code execute_javascript} in the LLM's mental model:
 * "I want to run a snippet, I pass code, I get the result." No
 * {@code python_create} dance, no RootDir-type awareness needed. Use
 * the lower-level {@code python_run(file, dirName)} only when you have
 * a persisted multi-file project.
 *
 * <p>The default RootDir is shared across calls within a session, so
 * subsequent {@code execute_python} invocations reuse the same venv —
 * pip-installed packages from {@code python_install} stay available.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutePythonTool implements Tool {

    /** Label of the default Python RootDir that {@code execute_python}
     *  creates on demand. Underscore prefix marks it as system-managed
     *  (same convention as {@code _user_*} / {@code _tenant} projects). */
    static final String DEFAULT_LABEL = "_python";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "code", Map.of(
                            "type", "string",
                            "description",
                                    "Python source. Executed in a fresh "
                                            + "process with the default Python "
                                            + "RootDir (`_python`) as working "
                                            + "directory. stdout / stderr are "
                                            + "captured and returned."),
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
                    "waitMs", Map.of(
                            "type", "integer",
                            "description",
                                    "Wall-clock timeout in milliseconds. Long-"
                                            + "running scripts return early with "
                                            + "status=RUNNING and a job id for "
                                            + "exec_status.")),
            "required", List.of("code"));

    private final WorkspaceService workspaceService;
    private final ExecManager execManager;
    private final ExecProperties properties;

    @Override
    public String name() {
        return "execute_python";
    }

    @Override
    public String description() {
        return "Execute Python on the brain server — the Python "
                + "analog of `execute_javascript`. Pass the source as "
                + "`code`; the tool sets up a default Python "
                + "environment (`_python` RootDir + venv, idempotent) "
                + "and runs the script immediately. stdout / stderr "
                + "come back as the tool result. Use for quick "
                + "calculations, data transforms, anything where you "
                + "want a one-shot Python execution without managing "
                + "RootDirs explicitly. For persisted multi-file "
                + "projects use the lower-level `python_run(file, "
                + "dirName)` with `python_create` / `python_install`.";
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
    public Set<String> labels() {
        return Set.of("executive", "side-effect");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Run a Python snippet, like execute_javascript but for Python";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String code = requireString(params, "code");
        String flags = stringOrNull(params, "flags");
        List<String> args = stringList(params, "args");
        long waitMs = waitMs(params, properties.getDefaultWaitMs());

        String tenantId = ctx.tenantId();
        String projectId = ctx.projectId();
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(projectId)) {
            throw new ToolException("execute_python requires tenant and project scope");
        }
        String creator = StringUtils.defaultIfBlank(ctx.processId(), ctx.sessionId());
        if (StringUtils.isBlank(creator)) {
            throw new ToolException("execute_python needs a process or session scope");
        }

        RootDirHandle handle = ensureDefaultPythonRootDir(tenantId, projectId, creator, ctx.sessionId());
        String dirName = handle.getDirName();

        // Write the inline script. Timestamp suffix keeps successive
        // calls from clobbering each other if the previous run is
        // still RUNNING and the user / LLM follows up via exec_status.
        String fileName = "_inline_" + System.currentTimeMillis() + ".py";
        try {
            Path written = workspaceService.write(tenantId, projectId, dirName, fileName, code);
            log.debug("execute_python: wrote {} chars to {}/{}",
                    code.length(), dirName, written.getFileName());
        } catch (RuntimeException e) {
            throw new ToolException("execute_python: failed to write script: " + e.getMessage(), e);
        }

        StringBuilder cmd = new StringBuilder(".venv/bin/python");
        if (StringUtils.isNotBlank(flags)) {
            cmd.append(' ').append(flags);
        }
        cmd.append(' ').append(PythonShellEscape.quote(fileName));
        for (String arg : args) {
            cmd.append(' ').append(PythonShellEscape.quote(arg));
        }

        Map<String, String> labels = Map.of(
                ExecLabels.KEY_SOURCE, ExecLabels.SOURCE_LLM_TOOL,
                ExecLabels.KEY_LANGUAGE, ExecLabels.LANG_PYTHON,
                ExecLabels.KEY_RUN_KIND, ExecLabels.RUN_KIND_SCRIPT);
        try {
            return execManager.submitTrackedAndRender(
                    tenantId, projectId,
                    ctx.sessionId(), ctx.processId(),
                    dirName, cmd.toString(), waitMs,
                    SubmitOptions.defaults().withLabels(labels));
        } catch (RuntimeException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    /**
     * Find-or-create the default Python RootDir for this project.
     * Lookup by label first (same idempotency rule as
     * {@link PythonCreateTool}); only create when absent.
     */
    private RootDirHandle ensureDefaultPythonRootDir(
            String tenantId, String projectId, String creator, String sessionId) {
        for (RootDirHandle h : workspaceService.listRootDirs(tenantId, projectId)) {
            if (!PythonHandler.TYPE.equals(h.getType())) continue;
            String label = h.getDescriptor() == null ? null : h.getDescriptor().getLabel();
            if (DEFAULT_LABEL.equals(label)) {
                return h;
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(PythonHandler.META_PYTHON_PATH, PythonHandler.DEFAULT_PYTHON_PATH);
        RootDirSpec spec = RootDirSpec.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .type(PythonHandler.TYPE)
                .creatorProcessId(creator)
                .sessionId(sessionId)
                .labelHint(DEFAULT_LABEL)
                .deleteOnCreatorClose(false)
                .metadata(metadata)
                .build();
        try {
            RootDirHandle handle = workspaceService.createRootDir(spec);
            log.info("execute_python: created default Python RootDir tenant='{}' "
                    + "project='{}' dirName='{}'", tenantId, projectId, handle.getDirName());
            return handle;
        } catch (WorkspaceException e) {
            throw new ToolException("execute_python: failed to provision Python RootDir: "
                    + e.getMessage(), e);
        }
    }

    private static String requireString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static @org.jspecify.annotations.Nullable String stringOrNull(
            Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return (raw instanceof String s && !s.isBlank()) ? s : null;
    }

    private static List<String> stringList(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private static long waitMs(Map<String, Object> params, long defaultMs) {
        Object raw = params == null ? null : params.get("waitMs");
        if (raw instanceof Number n) return Math.max(0, n.longValue());
        return defaultMs;
    }
}
