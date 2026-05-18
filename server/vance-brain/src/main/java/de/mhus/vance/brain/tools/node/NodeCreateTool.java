package de.mhus.vance.brain.tools.node;

import de.mhus.vance.shared.workspace.GitHandler;
import de.mhus.vance.shared.workspace.NodeHandler;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Ensures a Node RootDir exists. Idempotent on {@code label} (default
 * {@code "_jsengine"}). The underscore-prefix marks the folder as
 * system-managed — user-facing tooling typically hides it.
 *
 * <p>Mirrors {@code python_create}: if a node-type RootDir with the
 * requested label already exists, returns it with {@code status="exists"};
 * otherwise creates a fresh one (optionally git-cloned) and runs
 * {@code npm init -y} so subsequent {@code npm install} calls have a
 * {@code package.json} to update.
 */
@Component
@RequiredArgsConstructor
public class NodeCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "npmPath", Map.of(
                            "type", "string",
                            "description",
                                    "Path to the npm binary. Default: 'npm' "
                                            + "(resolved via PATH on the brain pod)."),
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
                                    "Optional dirName hint. Default: '_jsengine'. "
                                            + "Service appends a numeric suffix on collision."),
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
        return "node_create";
    }

    @Override
    public String description() {
        return "Ensure a Node scratch RootDir with a local node_modules tree "
                + "exists. Idempotent on 'label' (default '_jsengine'). "
                + "Returns the dirName so node_install and the JavaScript "
                + "engine's require() pathway can target it.";
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
        return "Ensure Node workspace (npm) RootDir exists (idempotent)";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = ctx.tenantId();
        String projectId = ctx.projectId();
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(projectId)) {
            throw new ToolException("node_create requires tenant and project scope");
        }
        String creator = StringUtils.defaultIfBlank(ctx.processId(), ctx.sessionId());
        if (StringUtils.isBlank(creator)) {
            throw new ToolException("node_create needs a process or session scope");
        }

        String npmPath = stringOr(params, "npmPath", NodeHandler.DEFAULT_NPM_PATH);
        String repoUrl = stringOrNull(params, "repoUrl");
        String branch = stringOrNull(params, "branch");
        String label = stringOr(params, "label", NodeHandler.DEFAULT_LABEL);
        String credentialAlias = stringOrNull(params, "credentialAlias");
        boolean asWorkingDir = params != null && Boolean.TRUE.equals(params.get("asWorkingDir"));

        RootDirHandle existing = findExistingNodeRootDir(tenantId, projectId, label);
        if (existing != null) {
            if (asWorkingDir) {
                workspaceService.setWorkingDir(tenantId, projectId, creator, existing.getDirName());
            }
            return response(existing, npmPath, repoUrl, asWorkingDir, "exists");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(NodeHandler.META_NPM_PATH, npmPath);
        if (repoUrl != null) metadata.put(GitHandler.META_REPO_URL, repoUrl);
        if (branch != null) metadata.put(GitHandler.META_BRANCH, branch);
        if (credentialAlias != null) metadata.put(GitHandler.META_CREDENTIAL_ALIAS, credentialAlias);

        RootDirSpec spec = RootDirSpec.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .type(NodeHandler.TYPE)
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
        return response(handle, npmPath, repoUrl, asWorkingDir, "created");
    }

    private @Nullable RootDirHandle findExistingNodeRootDir(
            String tenantId, String projectId, String label) {
        for (RootDirHandle h : workspaceService.listRootDirs(tenantId, projectId)) {
            if (!NodeHandler.TYPE.equals(h.getType())) continue;
            String existingLabel = h.getDescriptor() == null
                    ? null : h.getDescriptor().getLabel();
            if (label.equals(existingLabel)) {
                return h;
            }
        }
        return null;
    }

    private static Map<String, Object> response(
            RootDirHandle handle, String npmPath,
            @Nullable String repoUrl, boolean asWorkingDir, String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", handle.getDirName());
        out.put("path", handle.getPath().toString());
        out.put("npmPath", npmPath);
        if (repoUrl != null) out.put("repoUrl", repoUrl);
        out.put("workingDir", asWorkingDir);
        out.put("status", status);
        return out;
    }

    private static @Nullable String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static String stringOr(Map<String, Object> params, String key, String fallback) {
        String v = stringOrNull(params, key);
        return v == null ? fallback : v;
    }
}
