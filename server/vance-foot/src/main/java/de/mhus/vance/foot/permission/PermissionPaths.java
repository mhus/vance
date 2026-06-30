package de.mhus.vance.foot.permission;

import de.mhus.vance.foot.tools.file.ClientFilePaths;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/**
 * Path handling for the sandbox gate. Two jobs:
 *
 * <ol>
 *   <li>{@link #canonicalize(String)} — turn a tool's {@code path} param
 *       into the absolute, {@code ..}-collapsed, symlink-resolved path
 *       that rules are matched against. Without this,
 *       {@code ~/foo/../.ssh/id_rsa} would slip past a {@code ~/.ssh/**}
 *       deny rule.</li>
 *   <li>{@link #globMatcher(String)} — compile a permission glob
 *       (which may start with {@code ~} or be relative to the CWD) into
 *       an absolute {@link PathMatcher}, so it can be tested against the
 *       canonical subject path.</li>
 * </ol>
 *
 * <p>The CWD ({@code user.dir}) is read once at compile time — Foot is a
 * single-session process, so the working directory does not change
 * underneath us.
 */
public final class PermissionPaths {

    private PermissionPaths() {}

    /**
     * Absolute, normalised, symlink-resolved form of {@code raw}.
     * Expands a leading {@code ~}, resolves relative paths against the
     * CWD, collapses {@code .}/{@code ..} segments, and follows symlinks
     * via {@link Path#toRealPath}. Falls back to the
     * normalised-absolute path when the target does not exist yet (a
     * write to a not-yet-created file is legitimate) — the {@code ..}
     * collapse has already happened by then, so the bypass is closed
     * either way.
     */
    public static Path canonicalize(String raw) {
        Path p = ClientFilePaths.resolve(raw);
        if (!p.isAbsolute()) {
            p = Path.of(System.getProperty("user.dir", "")).resolve(p);
        }
        p = p.normalize();
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    /**
     * Compiles a permission path glob into an absolute matcher.
     * {@code ~}/{@code ~/…} expand to the home directory; a leading
     * {@code /} is taken as absolute; anything else (including
     * {@code ./…}) is resolved against the CWD. The resulting pattern is
     * matched against the {@link #canonicalize canonical} subject path.
     */
    public static PathMatcher globMatcher(String glob) {
        String absolute = expandPattern(glob);
        return FileSystems.getDefault().getPathMatcher("glob:" + absolute);
    }

    /**
     * String-level expansion of a glob to an absolute pattern. Pure
     * string work — NIO path normalisation would mangle the {@code **}
     * and {@code *} wildcards, so we concatenate by hand.
     */
    static String expandPattern(String glob) {
        String home = System.getProperty("user.home", "");
        String cwd = System.getProperty("user.dir", "");
        if (glob.equals("~")) return home;
        if (glob.startsWith("~/")) return home + "/" + glob.substring(2);
        if (glob.startsWith("/")) return glob;
        if (glob.startsWith("./")) return cwd + "/" + glob.substring(2);
        return cwd + "/" + glob;
    }
}
