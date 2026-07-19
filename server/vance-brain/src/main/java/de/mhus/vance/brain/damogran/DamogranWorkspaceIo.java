package de.mhus.vance.brain.damogran;

import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;

/**
 * WORK-target workspace helpers for the {@code git:*} importer/exporter, which
 * need a real server-side {@link Path} (JGit clones/commits there). File-byte
 * import/export goes through {@link ComposeFileIo} (target-agnostic) instead.
 */
final class DamogranWorkspaceIo {

    private DamogranWorkspaceIo() {}

    /** Fail unless the compose runs against a WORK target with a local path. */
    static Path requireWorkRoot(DamogranContext ctx, String op) {
        Path root = ctx.workspacePath();
        if (!ctx.isWork() || root == null) {
            throw new DamogranException(op + " is only supported for target WORK (was: " + ctx.target() + ")");
        }
        return root;
    }

    /** Confined workspace path for a workspace-relative path. */
    static Path resolve(WorkspaceService workspaceService, DamogranContext ctx, String relativePath) {
        return workspaceService.resolve(ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(), relativePath);
    }
}
