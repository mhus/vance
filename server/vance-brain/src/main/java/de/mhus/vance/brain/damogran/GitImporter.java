package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Imports a git repository into the workspace: {@code from: git:<url>},
 * {@code to: <workspace-dir>}. Clones on first use, pulls on re-run.
 * Options: {@code branch}, {@code credentialAlias}.
 */
@Component
class GitImporter implements DamogranImporter {

    private final WorkspaceService workspaceService;
    private final GitService gitService;

    GitImporter(WorkspaceService workspaceService, GitService gitService) {
        this.workspaceService = workspaceService;
        this.gitService = gitService;
    }

    @Override
    public Set<String> schemes() {
        return Set.of("git");
    }

    @Override
    public void doImport(DamogranContext ctx, ImportEntry entry) {
        DamogranWorkspaceIo.requireWorkRoot(ctx, "git import");
        String url = DamogranUri.stripGit(entry.from());
        Path dir = DamogranWorkspaceIo.resolve(workspaceService, ctx, entry.to());
        gitService.cloneOrPull(dir, url, entry.option("branch"),
                ctx.tenantId(), ctx.projectId(), entry.option("credentialAlias"));
    }
}
