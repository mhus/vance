package de.mhus.vance.brain.damogran;

import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared workspace filesystem helpers for importers/exporters — resolve a
 * workspace-relative path (confined via {@link WorkspaceService}) and read/write
 * bytes. All import/export in v1 is WORK-target only (server-side workspace).
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

    static void writeBytes(WorkspaceService workspaceService, DamogranContext ctx,
                           String relativePath, byte[] bytes) {
        Path resolved = resolve(workspaceService, ctx, relativePath);
        try {
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.write(resolved, bytes);
        } catch (IOException e) {
            throw new DamogranException("write failed for '" + relativePath + "': " + e.getMessage(), e);
        }
    }

    static byte[] readBytes(WorkspaceService workspaceService, DamogranContext ctx,
                            String relativePath, long maxBytes) {
        return workspaceService.readBytes(
                ctx.tenantId(), ctx.projectId(), ctx.workspaceDirName(), relativePath, maxBytes);
    }
}
