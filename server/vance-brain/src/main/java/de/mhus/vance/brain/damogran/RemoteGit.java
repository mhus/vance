package de.mhus.vance.brain.damogran;

import org.jspecify.annotations.Nullable;

/**
 * Builds the shell commands that realise {@code git:*} import/export on a
 * CLIENT/DAEMON target — where there is no managed workspace and no jgit, only
 * the remote host's own {@code git} reached via {@code exec_run}. Paths are
 * relative to the remote's working directory (same as every other remote exec
 * task); credentials come from the remote host's own git setup (ssh keys,
 * credential helper) — a {@code credentialAlias} (WORK-only, vault-backed) is
 * rejected upstream.
 *
 * <p>Pure command construction only (POSIX {@code sh}); the runner runs the
 * string via {@code exec_run} and turns a non-zero exit into a failure. Kept
 * separate so the quoting and clone-or-pull / commit-push shapes are unit-tested
 * without touching the exec layer.
 */
final class RemoteGit {

    private RemoteGit() {}

    static boolean isGit(String uri) {
        return "git".equals(DamogranUri.scheme(uri));
    }

    /**
     * Idempotent: pull {@code --ff-only} when {@code dir} is already a clone,
     * else a fresh {@code git clone} (optionally of {@code branch}).
     */
    static String cloneOrPullCommand(String url, String dir, @Nullable String branch) {
        String qUrl = sh(url);
        String qDir = sh(dir);
        String branchArg = isBlank(branch) ? "" : " --branch " + sh(branch);
        return "if [ -d " + qDir + "/.git ]; then "
                + "git -C " + qDir + " pull --ff-only; "
                + "else git clone" + branchArg + " " + qUrl + " " + qDir + "; fi";
    }

    /**
     * Stage all changes, commit <em>only if</em> something is staged (so a no-op
     * export doesn't fail on "nothing to commit"), and — unless {@code push} is
     * false — point {@code origin} at {@code url} and push {@code HEAD} (to
     * {@code branch} when given).
     */
    static String commitPushCommand(
            String dir, String url, @Nullable String branch, String message, boolean push) {
        String qDir = sh(dir);
        StringBuilder sb = new StringBuilder();
        sb.append("git -C ").append(qDir).append(" add -A && ");
        sb.append("if ! git -C ").append(qDir).append(" diff --cached --quiet; then ");
        sb.append("git -C ").append(qDir).append(" commit -m ").append(sh(message)).append("; fi");
        if (push) {
            // Re-point origin idempotently (set-url fails if origin is absent → add).
            sb.append(" && (git -C ").append(qDir).append(" remote set-url origin ").append(sh(url));
            sb.append(" || git -C ").append(qDir).append(" remote add origin ").append(sh(url)).append(")");
            String refspec = isBlank(branch) ? "HEAD" : "HEAD:" + branch;
            sb.append(" && git -C ").append(qDir).append(" push origin ").append(sh(refspec));
        }
        return sb.toString();
    }

    /** POSIX single-quote a token so spaces/metacharacters are literal. */
    static String sh(String token) {
        return "'" + token.replace("'", "'\\''") + "'";
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
