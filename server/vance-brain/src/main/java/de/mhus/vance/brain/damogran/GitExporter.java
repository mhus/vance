package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Exports a workspace git working tree back to a repository:
 * {@code from: <workspace-dir>}, {@code to: git:<url>}. Stages all changes,
 * commits, and (unless {@code push: false}) pushes {@code HEAD} to
 * {@code branch}. Options: {@code branch}, {@code message}, {@code push}
 * (default {@code true}), {@code credentialAlias}. Target-agnostic — delegates
 * to the run's {@link ComposeGit} backend.
 *
 * <p>The source dir must be a git working tree — clone it first via a git
 * import (or use a {@code type: git} workspace).
 */
@Component
class GitExporter implements DamogranExporter {

    private static final String DEFAULT_MESSAGE = "Update from Damogran";

    @Override
    public Set<String> schemes() {
        return Set.of("git");
    }

    @Override
    public void doExport(DamogranContext ctx, ExportEntry entry) {
        if (entry.from() == null || entry.from().isBlank()) {
            throw new DamogranException("git export requires a 'from' working-tree directory");
        }
        String message = entry.option("message");
        ctx.requireGit("git export").exportRepo(
                entry.from(), DamogranUri.stripGit(entry.to()), entry.option("branch"),
                message != null ? message : DEFAULT_MESSAGE, entry.boolOption("push", true),
                entry.option("credentialAlias"));
    }
}
