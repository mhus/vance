package de.mhus.vance.shared.workspace;

import de.mhus.vance.exec.RootDirException;
import de.mhus.vance.exec.RootPaths;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/**
 * Spring access to the workspace-path confinement: every relative path a
 * {@code work_file_*} / {@code work_exec_*} tool addresses must resolve to a
 * location <b>inside</b> its RootDir. The rules — syntactic {@code ..} collapse,
 * symlink escape, the known check-vs-I/O limitation — live in {@link RootPaths}
 * ({@code vance-exec}), shared with the runner; this bean only translates
 * {@link RootDirException} into {@link WorkspaceException}.
 *
 * <p>{@link WorkspaceService#resolve} delegates here; tools never resolve
 * paths themselves.
 */
@Service
public class WorkspaceRootService {

    /**
     * Resolves {@code relativePath} within {@code base}, enforcing containment.
     * Returns the absolute, normalised target path.
     *
     * @throws WorkspaceException on blank/NUL input or any escape attempt
     */
    public Path resolveWithin(Path base, String relativePath) {
        try {
            return RootPaths.resolveWithin(base, relativePath);
        } catch (RootDirException e) {
            throw new WorkspaceException(e.getMessage(), e);
        }
    }

    /** True when {@code candidate} is contained in {@code base} (symlink-aware). */
    public boolean isWithin(Path base, Path candidate) {
        return RootPaths.isWithin(base, candidate);
    }
}
