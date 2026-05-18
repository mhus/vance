package de.mhus.vance.brain.tools.node;

import de.mhus.vance.shared.workspace.NodeHandler;
import de.mhus.vance.shared.workspace.RootDirHandle;
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
 * Installs one npm package into the named Node RootDir via
 * {@code npm install --save --ignore-scripts}. Synchronous — blocks
 * until npm exits, then returns. {@code --ignore-scripts} is hard-
 * wired so a malicious package's {@code postinstall} hook cannot run
 * at install time; the brain only ever reads from node_modules.
 *
 * <p>Multi-package installs: provide {@code packages} (array) instead
 * of {@code package}; runs one npm invocation with all specs so the
 * caller doesn't chain N tool calls.
 */
@Component
@RequiredArgsConstructor
public class NodeInstallTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "package", Map.of(
                            "type", "string",
                            "description",
                                    "Single npm package spec (e.g. 'lodash', "
                                            + "'dayjs@^1.11', '@types/node'). "
                                            + "Provide this OR 'packages'."),
                    "packages", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                                    "Multiple npm specs installed in one npm "
                                            + "invocation. Faster than chaining "
                                            + "node_install. Provide this OR 'package'."),
                    "dirName", Map.of(
                            "type", "string",
                            "description",
                                    "Optional Node RootDir name. Defaults to the "
                                            + "current process's working RootDir.")),
            "required", List.of());

    private final WorkspaceService workspaceService;
    private final NodeHandler nodeHandler;

    @Override
    public String name() {
        return "node_install";
    }

    @Override
    public String description() {
        return "Install one or more npm packages into the named Node "
                + "RootDir (npm install --save --ignore-scripts). "
                + "Provide 'package' (single spec) or 'packages' (array).";
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
        return "Install npm packages into a Node RootDir";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = ctx.tenantId();
        String projectId = ctx.projectId();
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(projectId)) {
            throw new ToolException("node_install requires tenant and project scope");
        }

        List<String> specs = collectSpecs(params);
        if (specs.isEmpty()) {
            throw new ToolException("node_install requires 'package' or 'packages'");
        }

        RootDirHandle handle = resolveRootDir(tenantId, projectId, params, ctx);

        try {
            // One npm invocation per spec — npm install <pkg> with --save
            // updates package.json correctly per package and avoids
            // surprises with version pinning across a multi-spec call.
            for (String spec : specs) {
                nodeHandler.install(handle, spec);
            }
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", handle.getDirName());
        out.put("installed", specs);
        out.put("count", specs.size());
        return out;
    }

    private static List<String> collectSpecs(@Nullable Map<String, Object> params) {
        if (params == null) return List.of();
        List<String> out = new java.util.ArrayList<>();
        Object single = params.get("package");
        if (single instanceof String s && !s.isBlank()) out.add(s.trim());
        Object many = params.get("packages");
        if (many instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String s && !s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }

    private RootDirHandle resolveRootDir(
            String tenantId, String projectId,
            @Nullable Map<String, Object> params, ToolInvocationContext ctx) {
        String dirName = params == null ? null : (params.get("dirName") instanceof String s
                ? s : null);
        if (dirName != null && !dirName.isBlank()) {
            return workspaceService.getRootDir(tenantId, projectId, dirName)
                    .orElseThrow(() -> new ToolException(
                            "Node RootDir '" + dirName + "' not found in project "
                                    + projectId));
        }
        String creator = StringUtils.defaultIfBlank(ctx.processId(), ctx.sessionId());
        String workingDir = creator == null ? null
                : workspaceService.getWorkingDir(tenantId, projectId, creator).orElse(null);
        if (workingDir == null) {
            // No working dir set — try the conventional default label.
            return findByLabel(tenantId, projectId, NodeHandler.DEFAULT_LABEL)
                    .orElseThrow(() -> new ToolException(
                            "No Node RootDir found in project " + projectId
                                    + ". Run node_create first."));
        }
        return workspaceService.getRootDir(tenantId, projectId, workingDir)
                .orElseThrow(() -> new ToolException(
                        "Working RootDir '" + workingDir
                                + "' not found in project " + projectId));
    }

    private java.util.Optional<RootDirHandle> findByLabel(
            String tenantId, String projectId, String label) {
        for (RootDirHandle h : workspaceService.listRootDirs(tenantId, projectId)) {
            if (NodeHandler.TYPE.equals(h.getType())
                    && h.getDescriptor() != null
                    && label.equals(h.getDescriptor().getLabel())) {
                return java.util.Optional.of(h);
            }
        }
        return java.util.Optional.empty();
    }
}
