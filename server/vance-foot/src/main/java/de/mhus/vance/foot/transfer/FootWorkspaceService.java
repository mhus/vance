package de.mhus.vance.foot.transfer;

import de.mhus.vance.foot.config.FootConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Sandboxed file access for the Foot-side transfer workspace. Resolves
 * Brain-supplied {@code (projectId, userPath)} pairs to absolute paths
 * under {@code <root>/<tenant>/<projectId>/} and refuses anything that
 * escapes — either via {@code "../"} segments, an absolute path, or a
 * symlink that resolves outside the sandbox.
 *
 * <p>This is independent from the existing {@code client_file_*} tools
 * which operate on arbitrary user paths. Transfers go through here so
 * the Brain's reach into the local filesystem stays bounded.
 */
@Service
@Slf4j
public class FootWorkspaceService {

    private final FootWorkspaceProperties properties;
    private final FootConfig footConfig;

    public FootWorkspaceService(FootWorkspaceProperties properties, FootConfig footConfig) {
        this.properties = properties;
        this.footConfig = footConfig;
    }

    /**
     * Absolute path to the project sandbox root. Creates the folder
     * (and any missing ancestors) on first call so callers can rely on
     * its existence.
     */
    public Path projectRoot(String projectId) {
        requireProject(projectId);
        Path root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
        String tenant = effectiveTenant();
        Path projectRoot = root.resolve(tenant).resolve(projectId);
        try {
            Files.createDirectories(projectRoot);
        } catch (IOException e) {
            throw new TransferPathException(
                    "failed to create foot workspace project root: " + projectRoot, e);
        }
        return projectRoot;
    }

    /**
     * Resolve {@code userPath} for a write under the given project's
     * sandbox. Creates parent directories on success. Throws
     * {@link TransferPathException} if the resolved path escapes.
     */
    public Path resolveForWrite(String projectId, String userPath) {
        Path resolved = resolve(projectId, userPath);
        Path parent = resolved.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new TransferPathException(
                        "failed to create parent directories for " + resolved, e);
            }
        }
        return resolved;
    }

    /**
     * Resolve {@code userPath} for a read under the given project's
     * sandbox. The file must exist; throws {@link TransferPathException}
     * otherwise. Does not follow symlinks at the leaf — a symlink whose
     * target is outside the sandbox is rejected here, even if the link
     * itself sits inside.
     */
    public Path resolveForRead(String projectId, String userPath) {
        Path resolved = resolve(projectId, userPath);
        if (!Files.exists(resolved)) {
            throw new TransferPathException("file not found: " + userPath);
        }
        if (Files.isSymbolicLink(resolved)) {
            try {
                Path target = resolved.toRealPath();
                Path realProjectRoot = projectRoot(projectId).toRealPath();
                if (!target.startsWith(realProjectRoot)) {
                    throw new TransferPathException("symlink target escapes sandbox: " + userPath);
                }
            } catch (IOException e) {
                throw new TransferPathException("failed to resolve symlink: " + userPath, e);
            }
        }
        return resolved;
    }

    private Path resolve(String projectId, String userPath) {
        if (isBlank(userPath)) {
            throw new TransferPathException("userPath is empty");
        }
        Path projectRoot;
        try {
            projectRoot = projectRoot(projectId).toRealPath();
        } catch (IOException e) {
            throw new TransferPathException(
                    "failed to canonicalize project root for " + projectId, e);
        }
        String stripped = stripLeading(userPath);
        Path candidate = projectRoot.resolve(stripped).normalize();
        if (!candidate.startsWith(projectRoot)) {
            throw new TransferPathException("path escapes project sandbox: " + userPath);
        }
        return candidate;
    }

    /**
     * Strip a leading {@code /} or {@code \\} so a path that the LLM
     * formulated as absolute is treated as sandbox-relative rather than
     * silently escaping via {@link Path#resolve}.
     */
    private static String stripLeading(String userPath) {
        String s = userPath;
        while (s.startsWith("/") || s.startsWith("\\")) {
            s = s.substring(1);
        }
        return s;
    }

    private static void requireProject(String projectId) {
        if (isBlank(projectId)) {
            throw new TransferPathException("projectId is empty");
        }
    }

    private String effectiveTenant() {
        String tenant = footConfig.getAuth().getTenant();
        if (isBlank(tenant)) {
            return "_";
        }
        return tenant;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public FootWorkspaceProperties properties() {
        return properties;
    }
}
