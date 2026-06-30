package de.mhus.vance.shared.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Central authority for workspace-path confinement: every relative path a
 * {@code work_file_*} / {@code work_exec_*} tool addresses must resolve to
 * a location <b>inside</b> its RootDir (the "workspace folder"). Anything
 * that escapes is rejected — no prompt, hard {@link WorkspaceException}.
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
 * <p>{@link WorkspaceService#resolve} delegates here; tools never resolve
 * paths themselves.
 */
@Service
@Slf4j
public class WorkspaceRootService {

    /**
     * Resolves {@code relativePath} within {@code base}, enforcing
     * containment (syntactic + symlink). Returns the absolute, normalised
     * target path.
     *
     * @throws WorkspaceException on blank/NUL input or any escape attempt
     */
    public Path resolveWithin(Path base, String relativePath) {
        if (StringUtils.isBlank(relativePath)) {
            throw new WorkspaceException("Path is required");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new WorkspaceException("Path contains NUL byte");
        }
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new WorkspaceException("Path escapes workspace folder: '" + relativePath + "'");
        }
        assertRealWithin(base, resolved, relativePath);
        return resolved;
    }

    /** True when {@code candidate} is contained in {@code base} (symlink-aware). */
    public boolean isWithin(Path base, Path candidate) {
        try {
            assertRealWithin(base, candidate.normalize(), candidate.toString());
            return candidate.normalize().startsWith(base);
        } catch (WorkspaceException e) {
            return false;
        }
    }

    private void assertRealWithin(Path base, Path resolved, String relativePath) {
        Path realBase;
        try {
            realBase = base.toRealPath();
        } catch (IOException e) {
            throw new WorkspaceException("Workspace folder unavailable: " + e.getMessage(), e);
        }
        // Deepest ancestor that exists on disk (NOFOLLOW so a dangling
        // symlink still counts as "present" and gets caught below).
        Path probe = resolved;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            throw new WorkspaceException("Path escapes workspace folder: '" + relativePath + "'");
        }
        Path realProbe;
        try {
            realProbe = probe.toRealPath();
        } catch (IOException e) {
            // Dangling symlink or unreadable link target — refuse.
            throw new WorkspaceException(
                    "Path escapes workspace folder (symlink): '" + relativePath + "'");
        }
        if (!realProbe.startsWith(realBase)) {
            throw new WorkspaceException(
                    "Path escapes workspace folder (symlink): '" + relativePath + "'");
        }
    }
}
