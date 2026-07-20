package de.mhus.vance.brain.damogran;

import org.jspecify.annotations.Nullable;

/**
 * CLIENT/DAEMON {@link ComposeGit}: runs the remote host's own {@code git}
 * through the run's {@link ComposeExec} — there is no jgit and no managed
 * workspace here. {@link RemoteGit} builds the shell (idempotent clone-or-pull
 * on import; add-all + commit-if-changed + push {@code HEAD[:branch]} on
 * export); a non-zero exit (e.g. no {@code git} on the host) fails the run.
 * A vault-backed {@code credentialAlias} has no meaning remotely and is
 * rejected — the host's own git credentials (ssh key / credential helper) apply.
 */
final class RemoteComposeGit implements ComposeGit {

    private static final int DEFAULT_GIT_DEADLINE_SECONDS = DamogranTaskSupport.DEFAULT_EXEC_DEADLINE_SECONDS;

    private final ComposeExec exec;
    private final String target;

    RemoteComposeGit(ComposeExec exec, String target) {
        this.exec = exec;
        this.target = target;
    }

    @Override
    public void importRepo(String url, String toDir, @Nullable String branch, @Nullable String credentialAlias) {
        rejectCredentialAlias(credentialAlias, "git import");
        runGit(RemoteGit.cloneOrPullCommand(url, toDir, branch), "git import " + url);
    }

    @Override
    public void exportRepo(String fromDir, String url, @Nullable String branch,
                           String message, boolean push, @Nullable String credentialAlias) {
        rejectCredentialAlias(credentialAlias, "git export");
        runGit(RemoteGit.commitPushCommand(fromDir, url, branch, message, push), "git export " + url);
    }

    private void runGit(String command, String label) {
        // ComposeExec.run normalises failures into a non-ok result (never throws
        // for a command/backend failure), so a missing git or a push error
        // surfaces here as a DamogranException — consistent with the other
        // importers/exporters, which throw rather than return.
        ComposeExec.Result r = exec.run(command, DEFAULT_GIT_DEADLINE_SECONDS);
        if (!r.ok()) {
            String detail = r.stderr().isBlank() ? r.stdout() : r.stderr();
            throw new DamogranException(label + " exit code " + r.exitCode() + ": " + detail.strip());
        }
    }

    private void rejectCredentialAlias(@Nullable String alias, String op) {
        if (alias != null && !alias.isBlank()) {
            throw new DamogranException(op + " on " + target + " target does not support "
                    + "'credentialAlias' (vault-backed, WORK-only) — the remote host's own git "
                    + "credentials (ssh key / credential helper) are used");
        }
    }
}
