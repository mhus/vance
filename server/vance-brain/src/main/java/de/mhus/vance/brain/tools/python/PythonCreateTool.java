package de.mhus.vance.brain.tools.python;

import de.mhus.vance.shared.workspace.GitHandler;
import de.mhus.vance.shared.workspace.PythonHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.RootDirSpec;
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
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Ensures a Python RootDir exists: idempotent on the requested
 * {@code label} (default {@code "python"}). If a python-type RootDir
 * with that label is already in the project, the tool returns its
 * dirName + path with {@code status="exists"} and leaves the
 * existing venv untouched. Otherwise it creates a fresh RootDir,
 * cloning a git repo when {@code repoUrl} is set and running
 * {@code python -m venv .venv}; the response carries
 * {@code status="created"}.
 *
 * <p>The idempotency layer addresses an observed LLM failure mode:
 * Gemini Flash would loop on {@code python_create} across turns even
 * when the recipe prompt asked it not to. Making the tool itself a
 * no-op for the default case removes the foot-gun.
 *
 * <p>Optionally promotes the resulting RootDir to the current
 * process's working RootDir so subsequent {@code workspace_*} and
 * {@code python_*} calls without {@code dirName} default to it.
 */
@Component
@RequiredArgsConstructor
public class PythonCreateTool implements Tool {

    private static final String DEFAULT_LABEL = "python";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "pythonPath", Map.of(
                            "type", "string",
                            "description",
                                    "Path to the Python interpreter used to build the venv. "
                                            + "Default: 'python3' (resolved via PATH on the brain pod). "
                                            + "Stored in the descriptor as informational only — recover "
                                            + "on another pod uses that pod's local python3."),
                    "repoUrl", Map.of(
                            "type", "string",
                            "description",
                                    "Optional git clone URL for source persistence. "
                                            + "Without a remote, the RootDir cannot be suspended."),
                    "branch", Map.of(
                            "type", "string",
                            "description", "Branch to check out when 'repoUrl' is set. Default: main."),
                    "label", Map.of(
                            "type", "string",
                            "description",
                                    "Optional dirName hint. Default: 'python'. Service appends "
                                            + "a numeric suffix on collision."),
                    "asWorkingDir", Map.of(
                            "type", "boolean",
                            "description",
                                    "If true, register this RootDir as the current process's "
                                            + "working RootDir."),
                    "credentialAlias", Map.of(
                            "type", "string",
                            "description",
                                    "Alias resolved by the credential store for authenticated "
                                            + "clones. Optional.")),
            "required", List.of());

    private final WorkspaceService workspaceService;

    @Override
    public String name() {
        return "python_create";
    }

    @Override
    public String description() {
        return "Ensure a Python workspace RootDir with a local .venv exists. "
                + "Idempotent on 'label' (default 'python') — if a Python "
                + "RootDir with that label is already there, returns it "
                + "untouched (status='exists') rather than creating a "
                + "parallel one. Optional git repo URL for source "
                + "persistence. Returns the dirName so python_install, "
                + "python_run and workspace_* tools can operate inside it.";
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
        return "Ensure Python venv RootDir exists (idempotent)";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = ctx.tenantId();
        String projectId = ctx.projectId();
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(projectId)) {
            throw new ToolException("python_create requires tenant and project scope");
        }
        String creator = StringUtils.defaultIfBlank(ctx.processId(), ctx.sessionId());
        if (StringUtils.isBlank(creator)) {
            throw new ToolException("python_create needs a process or session scope");
        }

        String pythonPath = stringOr(params, "pythonPath", PythonHandler.DEFAULT_PYTHON_PATH);
        String repoUrl = stringOrNull(params, "repoUrl");
        String branch = stringOrNull(params, "branch");
        String label = stringOr(params, "label", DEFAULT_LABEL);
        String credentialAlias = stringOrNull(params, "credentialAlias");
        boolean asWorkingDir = params != null && Boolean.TRUE.equals(params.get("asWorkingDir"));

        // Idempotency: if a python-type RootDir with this label already
        // exists, return it instead of creating a parallel one. The LLM
        // looping on python_create across turns was a real observed
        // failure mode; the recipe prompt can't reliably gate it.
        RootDirHandle existing = findExistingPythonRootDir(tenantId, projectId, label);
        if (existing != null) {
            if (asWorkingDir) {
                workspaceService.setWorkingDir(tenantId, projectId, creator, existing.getDirName());
            }
            return response(existing, pythonPath, repoUrl, asWorkingDir, "exists");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(PythonHandler.META_PYTHON_PATH, pythonPath);
        if (repoUrl != null) metadata.put(GitHandler.META_REPO_URL, repoUrl);
        if (branch != null) metadata.put(GitHandler.META_BRANCH, branch);
        if (credentialAlias != null) metadata.put(GitHandler.META_CREDENTIAL_ALIAS, credentialAlias);

        RootDirSpec spec = RootDirSpec.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .type(PythonHandler.TYPE)
                .creatorProcessId(creator)
                .sessionId(ctx.sessionId())
                .labelHint(label)
                .deleteOnCreatorClose(false)
                .metadata(metadata)
                .build();

        RootDirHandle handle;
        try {
            handle = workspaceService.createRootDir(spec);
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
        if (asWorkingDir) {
            workspaceService.setWorkingDir(tenantId, projectId, creator, handle.getDirName());
        }
        return response(handle, pythonPath, repoUrl, asWorkingDir, "created");
    }

    private @org.jspecify.annotations.Nullable RootDirHandle findExistingPythonRootDir(
            String tenantId, String projectId, String label) {
        for (RootDirHandle h : workspaceService.listRootDirs(tenantId, projectId)) {
            if (!PythonHandler.TYPE.equals(h.getType())) continue;
            String existingLabel = h.getDescriptor() == null
                    ? null : h.getDescriptor().getLabel();
            if (label.equals(existingLabel)) {
                return h;
            }
        }
        return null;
    }

    private static Map<String, Object> response(
            RootDirHandle handle, String pythonPath,
            @org.jspecify.annotations.Nullable String repoUrl,
            boolean asWorkingDir, String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", handle.getDirName());
        out.put("path", handle.getPath().toString());
        out.put("pythonPath", pythonPath);
        if (repoUrl != null) out.put("repoUrl", repoUrl);
        out.put("workingDir", asWorkingDir);
        out.put("status", status);
        return out;
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("SameParameterValue")
    private static String stringOr(Map<String, Object> params, String key, String fallback) {
        String v = stringOrNull(params, key);
        return v == null ? fallback : v;
    }
}
