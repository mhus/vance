package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.shared.workspace.GitHandler;
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
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Clones a git repository into a fresh scratch RootDir of type
 * {@code git}. Optionally promotes the new RootDir to the current
 * working RootDir so subsequent {@code workspace_*} calls without
 * {@code dirName} resolve to it.
 */
@Component
@RequiredArgsConstructor
public class GitCheckoutTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "repoUrl",
                                    Map.of(
                                            "type", "string",
                                            "description", "Git clone URL (HTTPS or SSH)."),
                            "branch",
                                    Map.of(
                                            "type", "string",
                                            "description", "Branch to check out. Default: main."),
                            "commit",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional commit SHA to detach to after cloning — the exact "
                                                    + "source match (e.g. the commit the running brain was "
                                                    + "built from) instead of today's branch tip."),
                            "depth",
                                    Map.of(
                                            "type",
                                            "integer",
                                            "description",
                                            "Optional shallow-clone depth — 1 = current snapshot, no "
                                                    + "history. Use it for analysis checkouts: seconds "
                                                    + "instead of minutes on large repos. Only for "
                                                    + "ephemeral RootDirs; a shallow clone cannot "
                                                    + "suspend/push."),
                            "label",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional dirName hint. The service appends "
                                                    + "a numeric suffix on collision."),
                            "asWorkingDir",
                                    Map.of(
                                            "type",
                                            "boolean",
                                            "description",
                                            "If true, register this RootDir as the current "
                                                    + "process's working RootDir. Subsequent "
                                                    + "workspace_* calls without 'dirName' "
                                                    + "default to it."),
                            "credentialAlias",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Alias resolved by the credential store for "
                                                    + "authenticated clones. Optional.")),
            "required", List.of("repoUrl"));

    private final WorkspaceService workspaceService;

    @Override
    public String name() {
        return "git_checkout";
    }

    @Override
    public String description() {
        return "Clone a git repository into a scratch RootDir. Returns "
                + "the dirName so other tools can read/write inside the "
                + "checkout. Pass asWorkingDir=true to make it the default "
                + "for subsequent workspace_* calls. Pass commit=<sha> to "
                + "detach to an exact revision instead of the branch tip, and "
                + "depth=1 for a shallow analysis clone.";
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
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "side-effect");
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Workspace shell/git/JS — destructive or rare";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = ctx.tenantId();
        String projectId = ctx.projectId();
        if (StringUtils.isBlank(tenantId)) {
            throw new ToolException("git_checkout requires a tenant scope");
        }
        if (StringUtils.isBlank(projectId)) {
            throw new ToolException("git_checkout requires a project scope");
        }
        String creator = ctx.processId();
        if (StringUtils.isBlank(creator)) {
            creator = ctx.sessionId();
        }
        if (StringUtils.isBlank(creator)) {
            throw new ToolException("git_checkout needs a process or session scope");
        }

        String repoUrl = stringOrThrow(params, "repoUrl");
        String branch = stringOrNull(params, "branch");
        String requestedCommit = stringOrNull(params, "commit");
        Integer depth = positiveIntOrNull(params, "depth");
        String label = stringOrNull(params, "label");
        String credentialAlias = stringOrNull(params, "credentialAlias");
        boolean asWorkingDir = params != null && Boolean.TRUE.equals(params.get("asWorkingDir"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(GitHandler.META_REPO_URL, repoUrl);
        if (branch != null) metadata.put(GitHandler.META_BRANCH, branch);
        if (requestedCommit != null) metadata.put(GitHandler.META_CHECKOUT_COMMIT, requestedCommit);
        if (depth != null) metadata.put(GitHandler.META_DEPTH, depth);
        if (credentialAlias != null) metadata.put(GitHandler.META_CREDENTIAL_ALIAS, credentialAlias);

        RootDirSpec spec = RootDirSpec.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .type(GitHandler.TYPE)
                .creatorProcessId(creator)
                .creatorEngine(StringUtils.defaultIfBlank(label, null))
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", handle.getDirName());
        out.put("path", handle.getPath().toString());
        out.put("repoUrl", repoUrl);
        if (branch != null) out.put("branch", branch);
        if (requestedCommit != null) out.put("requestedCommit", requestedCommit);
        Object commit = handle.getDescriptor().getMetadata() == null
                ? null
                : handle.getDescriptor().getMetadata().get(GitHandler.META_COMMIT);
        if (commit instanceof String c && !c.isBlank()) out.put("commit", c);
        out.put("workingDir", asWorkingDir);
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
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

    private static Integer positiveIntOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                int value = Integer.parseInt(text.trim());
                return value > 0 ? value : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
