package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Exports a workspace git working tree back to a repository:
 * {@code from: <workspace-dir>}, {@code to: git:<url>}. Stages all changes,
 * commits, and (unless {@code push: false}) pushes {@code HEAD} to
 * {@code branch}. Options: {@code branch}, {@code message}, {@code push}
 * (default {@code true}), {@code credentialAlias}.
 *
 * <p>The source dir must be a git working tree — clone it first via a git
 * import (or use a {@code type: git} workspace).
 */
@Component
class GitExporter implements DamogranExporter {

    private static final String DEFAULT_MESSAGE = "Update from Damogran";

    private final WorkspaceService workspaceService;
    private final GitService gitService;

    GitExporter(WorkspaceService workspaceService, GitService gitService) {
        this.workspaceService = workspaceService;
        this.gitService = gitService;
    }

    @Override
    public Set<String> schemes() {
        return Set.of("git");
    }

    @Override
    public void doExport(DamogranContext ctx, ExportEntry entry) {
        DamogranWorkspaceIo.requireWorkRoot(ctx, "git export");
        String url = DamogranUri.stripGit(entry.to());
        Path dir = DamogranWorkspaceIo.resolve(workspaceService, ctx, entry.from());
        String message = entry.option("message");
        gitService.commitAndPush(dir, url, entry.option("branch"),
                message != null ? message : DEFAULT_MESSAGE,
                entry.boolOption("push", true),
                ctx.tenantId(), ctx.projectId(), entry.option("credentialAlias"));
    }
}
