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
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Removes one or more npm packages from the named Node RootDir via
 * {@code npm uninstall --save}. Synchronous.
 */
@Component
@RequiredArgsConstructor
public class NodeUninstallTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "package", Map.of(
                            "type", "string",
                            "description",
                                    "Single npm package name. Provide this OR 'packages'."),
                    "packages", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description",
                                    "Multiple package names removed in one npm "
                                            + "invocation. Provide this OR 'package'."),
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
        return "node_uninstall";
    }

    @Override
    public String description() {
        return "Remove one or more npm packages from the named Node RootDir "
                + "(npm uninstall --save).";
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
        return "Remove npm packages from a Node RootDir";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = ctx.tenantId();
        String projectId = ctx.projectId();
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(projectId)) {
            throw new ToolException("node_uninstall requires tenant and project scope");
        }

        List<String> names = collectNames(params);
        if (names.isEmpty()) {
            throw new ToolException("node_uninstall requires 'package' or 'packages'");
        }

        RootDirHandle handle = resolveRootDir(tenantId, projectId, params, ctx);

        try {
            for (String name : names) {
                nodeHandler.uninstall(handle, name);
            }
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", handle.getDirName());
        out.put("uninstalled", names);
        out.put("count", names.size());
        return out;
    }

    private static List<String> collectNames(@Nullable Map<String, Object> params) {
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
                            "Node RootDir '" + dirName + "' not found in project " + projectId));
        }
        String creator = StringUtils.defaultIfBlank(ctx.processId(), ctx.sessionId());
        Optional<String> workingDir = creator == null
                ? Optional.empty()
                : workspaceService.getWorkingDir(tenantId, projectId, creator);
        if (workingDir.isEmpty()) {
            return findByLabel(tenantId, projectId, NodeHandler.DEFAULT_LABEL)
                    .orElseThrow(() -> new ToolException(
                            "No Node RootDir found in project " + projectId
                                    + ". Run node_create first."));
        }
        return workspaceService.getRootDir(tenantId, projectId, workingDir.get())
                .orElseThrow(() -> new ToolException(
                        "Working RootDir '" + workingDir.get()
                                + "' not found in project " + projectId));
    }

    private Optional<RootDirHandle> findByLabel(
            String tenantId, String projectId, String label) {
        for (RootDirHandle h : workspaceService.listRootDirs(tenantId, projectId)) {
            if (NodeHandler.TYPE.equals(h.getType())
                    && h.getDescriptor() != null
                    && label.equals(h.getDescriptor().getLabel())) {
                return Optional.of(h);
            }
        }
        return Optional.empty();
    }
}
