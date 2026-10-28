package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.exec.RootDirException;
import de.mhus.vance.exec.RootDirSearch;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Runs a {@link RootDirSearch} on a workspace RootDir for the {@code work_file_*}
 * search tools: finds the RootDir folder, hands over the tool's parameters, and turns
 * a failure into the {@link ToolException} the model reads.
 */
final class WorkspaceSearch {

    /** One search over a RootDir folder, as {@link RootDirSearch} offers them. */
    @FunctionalInterface
    interface Search {
        Map<String, Object> run(Path base, String dirName, @Nullable Map<String, Object> params);
    }

    private WorkspaceSearch() {}

    static Map<String, Object> run(
            WorkspaceService workspace,
            ToolInvocationContext ctx,
            @Nullable Map<String, Object> params,
            Search search) {
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, dirNameOf(params));
        try {
            Path base = workspace
                    .getRootDir(ctx.tenantId(), ctx.projectId(), dirName)
                    .orElseThrow(() -> new ToolException("Unknown RootDir: " + dirName))
                    .getPath();
            return search.run(base, dirName, params);
        } catch (RootDirException | WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static @Nullable String dirNameOf(@Nullable Map<String, Object> params) {
        Object raw = params == null ? null : params.get("dirName");
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
