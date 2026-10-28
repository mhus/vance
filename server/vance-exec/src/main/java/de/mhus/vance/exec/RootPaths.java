package de.mhus.vance.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Central authority for path confinement: every relative path a file or exec tool
 * addresses must resolve to a location <b>inside</b> its RootDir (the "workspace
 * folder"). Anything that escapes is rejected — no prompt, hard
 * {@link RootDirException}.
 *
 * <p>Two layers of defense:
 * <ol>
 *   <li><b>Syntactic</b> — {@code base.resolve(rel).normalize()} must still
 *       {@code startsWith(base)}; collapses {@code ..} traversal.</li>
 *   <li><b>Symlink</b> — the deepest existing ancestor of the resolved path
 *       is run through {@link Path#toRealPath} and must stay within the
 *       real base. This closes the gap where a symlink <em>inside</em> the
 *       RootDir points outside it (which {@code normalize()} alone misses).
 *       A dangling symlink resolves to an {@link IOException} and is
 *       rejected conservatively.</li>
 * </ol>
 *
 * <p>Tools never resolve paths themselves. The brain reaches this through
 * {@code WorkspaceRootService}; the runner calls it directly.
 */
public final class RootPaths {

    private RootPaths() {}

    /**
     * Resolves {@code relativePath} within {@code base}, enforcing
     * containment (syntactic + symlink). Returns the absolute, normalised
     * target path.
     *
     * <p><b>Known limitation — check-vs-I/O TOCTOU.</b> This validates the
     * deepest <em>existing</em> ancestor via {@code toRealPath} and returns the
     * logical path; callers then perform link-following I/O on it. A concurrent
     * writer in the same project could swap an intermediate path component for a
     * symlink pointing outside the RootDir <em>between</em> this check and the
     * I/O, so the I/O follows it out of confinement. Fully closing this needs
     * per-component {@code openat(O_NOFOLLOW)} semantics, which the JDK NIO API
     * does not expose. It is defense-in-depth held closed in practice by the
     * single-writer-per-path assumption within a project's trust boundary; the
     * racing party is already inside the same project. Do not rely on this as a
     * hard boundary against a hostile co-tenant that can write concurrently.
     *
     * @throws RootDirException on blank/NUL input or any escape attempt
     */
    public static Path resolveWithin(Path base, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new RootDirException("Path is required");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new RootDirException("Path contains NUL byte");
        }
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new RootDirException("Path escapes workspace folder: '" + relativePath + "'");
        }
        assertRealWithin(base, resolved, relativePath);
        return resolved;
    }

    /** True when {@code candidate} is contained in {@code base} (symlink-aware). */
    public static boolean isWithin(Path base, Path candidate) {
        try {
            assertRealWithin(base, candidate.normalize(), candidate.toString());
            return candidate.normalize().startsWith(base);
        } catch (RootDirException e) {
            return false;
        }
    }

    private static void assertRealWithin(Path base, Path resolved, String relativePath) {
        Path realBase;
        try {
            realBase = base.toRealPath();
        } catch (IOException e) {
            throw new RootDirException("Workspace folder unavailable: " + e.getMessage(), e);
        }
        // Deepest ancestor that exists on disk (NOFOLLOW so a dangling
        // symlink still counts as "present" and gets caught below).
        Path probe = resolved;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            throw new RootDirException("Path escapes workspace folder: '" + relativePath + "'");
        }
        Path realProbe;
        try {
            realProbe = probe.toRealPath();
        } catch (IOException e) {
            // Dangling symlink or unreadable link target — refuse.
            throw new RootDirException("Path escapes workspace folder (symlink): '" + relativePath + "'", e);
        }
        if (!realProbe.startsWith(realBase)) {
            throw new RootDirException("Path escapes workspace folder (symlink): '" + relativePath + "'");
        }
    }
}
