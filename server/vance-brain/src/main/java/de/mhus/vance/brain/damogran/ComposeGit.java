package de.mhus.vance.brain.damogran;

import org.jspecify.annotations.Nullable;

/**
 * Per-run {@code git:*} import/export backend — the one place git differs by
 * target. WORK uses jgit against the server RootDir ({@link WorkspaceComposeGit},
 * vault-backed {@code credentialAlias} supported); CLIENT/DAEMON run the remote
 * host's own {@code git} via exec ({@link RemoteComposeGit}). {@link GitImporter}
 * / {@link GitExporter} go through {@code ctx.git()} so the transport dispatches
 * {@code git:} like any other scheme — no target branching in the runner.
 */
public interface ComposeGit {

    /** Clone {@code url} into workspace-relative {@code toDir} (pull on re-run). */
    void importRepo(String url, String toDir, @Nullable String branch, @Nullable String credentialAlias);

    /** Stage+commit workspace-relative {@code fromDir} and (unless {@code push=false}) push. */
    void exportRepo(String fromDir, String url, @Nullable String branch,
                    String message, boolean push, @Nullable String credentialAlias);
}
