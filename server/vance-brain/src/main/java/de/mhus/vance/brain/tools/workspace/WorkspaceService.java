package de.mhus.vance.brain.tools.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Manages the per-session workspace: resolves relative paths into the
 * session's sub-directory, enforces the one cheap sandbox rule
 * ({@code resolve().normalize().startsWith(root)}), and exposes
 * read/write/list/delete operations over UTF-8 text.
 *
 * <p>Lazily creates the session directory on first write — listing or
 * reading an untouched session returns empty / not-found rather than
 * allocating a directory.
 *
 * <p>Nothing here does I/O on behalf of the think-engine control plane;
 * the file tools call through to this service. Keeping FS logic out of
 * the tool classes means the sandbox check is in one place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceService {

    private final WorkspaceProperties properties;

    /** Absolute path to a session's workspace directory. Created on demand. */
    public Path sessionRoot(String sessionId) {
        requireSession(sessionId);
        Path root = Path.of(properties.getBaseDir()).toAbsolutePath()
                .normalize().resolve(sessionId).normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new WorkspaceException(
                    "Cannot create workspace for session: " + e.getMessage(), e);
        }
        return root;
    }

    /**
     * Writes {@code content} to {@code relativePath}, creating parent
     * directories as needed. Overwrites if the file already exists.
     */
    public Path write(String sessionId, String relativePath, String content) {
        Path resolved = resolve(sessionId, relativePath);
        try {
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.writeString(resolved,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return resolved;
        } catch (IOException e) {
            throw new WorkspaceException("Write failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads {@code relativePath} as UTF-8. Returns up to {@code maxChars}
     * characters; the caller decides how to surface a truncation marker.
     * {@code maxChars <= 0} means unlimited.
     */
    public ReadResult read(String sessionId, String relativePath, int maxChars) {
        Path resolved = resolve(sessionId, relativePath);
        if (!Files.exists(resolved)) {
            throw new WorkspaceException("Not found: " + relativePath);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new WorkspaceException("Not a regular file: " + relativePath);
        }
        try {
            String full = Files.readString(resolved, StandardCharsets.UTF_8);
            if (maxChars > 0 && full.length() > maxChars) {
                return new ReadResult(full.substring(0, maxChars), true, full.length());
            }
            return new ReadResult(full, false, full.length());
        } catch (IOException e) {
            throw new WorkspaceException("Read failed: " + e.getMessage(), e);
        }
    }

    /** Relative path → absolute. Reports "file not found" to the caller. */
    public Path readablePath(String sessionId, String relativePath) {
        Path resolved = resolve(sessionId, relativePath);
        if (!Files.isRegularFile(resolved)) {
            throw new WorkspaceException("Not a regular file: " + relativePath);
        }
        return resolved;
    }

    /** Recursive file list, sorted, paths relative to the session root. */
    public List<String> list(String sessionId) {
        Path root = sessionRoot(sessionId);
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new WorkspaceException("List failed: " + e.getMessage(), e);
        }
    }

    /** Deletes a file. Returns {@code false} if it wasn't there to begin with. */
    public boolean delete(String sessionId, String relativePath) {
        Path resolved = resolve(sessionId, relativePath);
        try {
            if (Files.isDirectory(resolved)) {
                throw new WorkspaceException(
                        "Refusing to delete a directory: " + relativePath);
            }
            return Files.deleteIfExists(resolved);
        } catch (IOException e) {
            throw new WorkspaceException("Delete failed: " + e.getMessage(), e);
        }
    }

    /**
     * Wipes an entire session's workspace. Intended for session close /
     * admin cleanup — not exposed as a tool.
     */
    public void deleteAll(String sessionId) {
        requireSession(sessionId);
        Path root = Path.of(properties.getBaseDir()).toAbsolutePath()
                .normalize().resolve(sessionId).normalize();
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Workspace cleanup failed for {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Workspace walk failed for session {}: {}", sessionId, e.toString());
        }
    }

    /**
     * Resolves {@code relativePath} against the session root and checks
     * it stays inside. Throws {@link WorkspaceException} on escape
     * attempts or null/empty paths.
     */
    Path resolve(String sessionId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new WorkspaceException("Path is required");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new WorkspaceException("Path contains NUL byte");
        }
        Path root = sessionRoot(sessionId);
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new WorkspaceException(
                    "Path escapes workspace: '" + relativePath + "'");
        }
        return resolved;
    }

    private static void requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new WorkspaceException(
                    "Workspace tools require a session scope");
        }
    }

    /** Result of {@link #read} — full text plus truncation metadata. */
    public record ReadResult(String text, boolean truncated, int totalChars) {}
}
