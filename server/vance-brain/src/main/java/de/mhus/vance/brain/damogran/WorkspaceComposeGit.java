package de.mhus.vance.brain.damogran;

import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * WORK {@link ComposeGit}: jgit ({@link GitService}) against the server-side
 * RootDir. The working tree is a real filesystem path, so clone/pull and
 * commit/push happen locally and a vault-backed {@code credentialAlias} resolves
 * through {@code GitAuthProvider}.
 */
final class WorkspaceComposeGit implements ComposeGit {

    private final WorkspaceService workspaceService;
    private final GitService gitService;
    private final String tenantId;
    private final String projectId;
    private final String dirName;

    WorkspaceComposeGit(WorkspaceService workspaceService, GitService gitService,
                        String tenantId, String projectId, String dirName) {
        this.workspaceService = workspaceService;
        this.gitService = gitService;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.dirName = dirName;
    }

    @Override
    public void importRepo(String url, String toDir, @Nullable String branch, @Nullable String credentialAlias) {
        Path dir = workspaceService.resolve(tenantId, projectId, dirName, toDir);
        gitService.cloneOrPull(dir, url, branch, tenantId, projectId, credentialAlias);
    }

    @Override
    public void exportRepo(String fromDir, String url, @Nullable String branch,
                           String message, boolean push, @Nullable String credentialAlias) {
        Path dir = workspaceService.resolve(tenantId, projectId, dirName, fromDir);
        gitService.commitAndPush(dir, url, branch, message, push, tenantId, projectId, credentialAlias);
    }
}
