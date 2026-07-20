package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Imports a git repository into the workspace: {@code from: git:<url>},
 * {@code to: <workspace-dir>}. Clones on first use, pulls on re-run. Options:
 * {@code branch}, {@code credentialAlias}. Target-agnostic — delegates to the
 * run's {@link ComposeGit} backend (WORK jgit vs. remote host git via exec).
 */
@Component
class GitImporter implements DamogranImporter {

    @Override
    public Set<String> schemes() {
        return Set.of("git");
    }

    @Override
    public void doImport(DamogranContext ctx, ImportEntry entry) {
        if (entry.to() == null || entry.to().isBlank()) {
            throw new DamogranException("git import requires a 'to' target directory");
        }
        ctx.requireGit("git import").importRepo(
                DamogranUri.stripGit(entry.from()), entry.to(),
                entry.option("branch"), entry.option("credentialAlias"));
    }
}
