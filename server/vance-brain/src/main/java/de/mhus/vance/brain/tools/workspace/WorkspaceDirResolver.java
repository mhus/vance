package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceService;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

/**
 * Bridges {@link ToolInvocationContext} to the
 * {@link WorkspaceService}'s {@code dirName} parameter. When the LLM
 * passes an explicit {@code dirName}, it wins. Otherwise the resolver
 * falls back to a per-creator temp RootDir, lazy-created on first use.
 *
 * <p>Tools call this once at the top of {@code invoke}, then thread
 * the resulting {@code dirName} through subsequent service calls.
 */
public final class WorkspaceDirResolver {

    private WorkspaceDirResolver() {}

    public static String resolve(WorkspaceService workspace,
                                 ToolInvocationContext ctx,
                                 @Nullable String explicit) {
        String tenantId = ctx.tenantId();
        String projectId = ctx.projectId();
        if (StringUtils.isBlank(tenantId)) {
            throw new ToolException("Workspace tools require a tenant scope");
        }
        if (StringUtils.isBlank(projectId)) {
            throw new ToolException("Workspace tools require a project scope");
        }
        if (StringUtils.isNotBlank(explicit)) {
            return explicit;
        }
        String resolvedCreator = ctx.processId();
        if (StringUtils.isBlank(resolvedCreator)) {
            resolvedCreator = ctx.sessionId();
        }
        if (StringUtils.isBlank(resolvedCreator)) {
            throw new ToolException(
                    "Workspace tool needs a process or session scope when 'dirName' is not provided");
        }
        final String creator = resolvedCreator;
        return workspace.getWorkingDir(tenantId, projectId, creator)
                .orElseGet(() -> workspace.getOrCreateTempRootDir(tenantId, projectId, creator).getDirName());
    }
}
