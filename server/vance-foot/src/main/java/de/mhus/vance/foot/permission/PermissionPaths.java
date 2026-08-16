package de.mhus.vance.foot.permission;

import de.mhus.vance.foot.tools.file.ClientFilePaths;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public final class PermissionPaths {

    private PermissionPaths() {}

    /**
     * Absolute, normalised, symlink-resolved form of {@code raw}.
     * Expands a leading {@code ~}, resolves relative paths against the
     * CWD, collapses {@code .}/{@code ..} segments, and follows symlinks
     * via {@link Path#toRealPath}. When the target does not exist yet (a
     * write to a not-yet-created file is legitimate), it resolves the
     * deepest <em>existing</em> ancestor via {@code toRealPath} and
     * re-appends the remaining segments — so a <em>symlinked parent
     * directory</em> ({@code ~/link -> ~/.ssh}) is collapsed too, not just
     * {@code ..}. Without that, {@code ~/link/authorized_keys} would slip
     * past a {@code ~/.ssh/**} deny-floor and the OS write would follow the
     * symlink into the protected directory.
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
            return canonicalizeNonExistent(p);
        }
    }

    /**
     * The target itself does not exist — walk up to the deepest existing
     * ancestor, {@code toRealPath}-resolve it (collapsing symlinked parents),
     * then re-append the not-yet-existing tail segments in order.
     */
    private static Path canonicalizeNonExistent(Path normalized) {
        java.util.Deque<Path> tail = new java.util.ArrayDeque<>();
        Path cursor = normalized;
        while (cursor != null) {
            try {
                Path real = cursor.toRealPath();
                for (Path segment : tail) {
                    real = real.resolve(segment);
                }
                return real.normalize();
            } catch (IOException e) {
                Path name = cursor.getFileName();
                if (name != null) {
                    tail.addFirst(name);
                }
                cursor = cursor.getParent();
            }
        }
        return normalized; // no existing ancestor (unexpected for an absolute path)
    }

    /**
     * Compiles a permission path glob into an absolute matcher. Four
     * forms, and every rule states its own scope — there is deliberately
     * no file-level "base" switch, because in an allow-list a rule's
     * meaning must not depend on a distant line:
     *
     * <ul>
     *   <li>{@code ~} / {@code ~/…} — relative to the home directory</li>
     *   <li>{@code /…} — absolute. Use {@code /**}{@code /name/**} to
     *       match a folder wherever it sits in the filesystem.</li>
     *   <li>{@code ./…} — relative to the CWD</li>
     *   <li>anything else — also relative to the CWD</li>
     * </ul>
     *
     * <p>The last form is the trap: a bare {@code **}{@code /target/**}
     * looks filesystem-wide and silently becomes
     * {@code <cwd>/**}{@code /target/**}, which matches nothing. That
     * cost a full benchmark night — every {@code client_file_write} was
     * refused while the policy file looked correct. A bare pattern
     * starting with {@code **} can only be that mistake, so it is logged
     * rather than left silent.
     *
     * <p>The resulting pattern is matched against the
     * {@link #canonicalize canonical} subject path.
     */
    public static PathMatcher globMatcher(String glob) {
        warnIfAccidentallyCwdRelative(glob);
        String absolute = expandPattern(glob);
        return FileSystems.getDefault().getPathMatcher("glob:" + absolute);
    }

    private static void warnIfAccidentallyCwdRelative(String glob) {
        // `**` on its own is the correct spelling for "the working
        // directory subtree" — only a bare `**/…` is the mistake.
        if (glob.startsWith("**/")) {
            log.warn("Permission path rule '{}' is CWD-relative and expands to '{}' — "
                            + "it will match nothing. For a folder anywhere in the "
                            + "filesystem write '/{}'; for the working directory write "
                            + "'**' on its own.",
                    glob, expandPattern(glob), glob);
        }
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
