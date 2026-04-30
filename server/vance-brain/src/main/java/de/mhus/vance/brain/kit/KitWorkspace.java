package de.mhus.vance.brain.kit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Manages tmp directories for kit operations — clone targets, build
 * trees, export staging. Each operation gets its own UUID-named
 * directory under {@code vance.kit.workspace} (default: java tmp +
 * {@code /vance-kits/}). Directories survive on crash so a developer
 * can inspect them; successful operations call {@link #remove}.
 */
@Service
@Slf4j
public class KitWorkspace {

    private final Path root;

    public KitWorkspace(@Value("${vance.kit.workspace:#{null}}") String configured) {
        Path resolved;
        if (configured != null && !configured.isBlank()) {
            resolved = Path.of(configured);
        } else {
            resolved = Path.of(System.getProperty("java.io.tmpdir"), "vance-kits");
        }
        this.root = resolved;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new KitException("Failed to create kit workspace at " + this.root, e);
        }
        log.info("Kit workspace at '{}'", this.root);
    }

    /** Allocates a fresh sub-directory and returns its path. */
    public Path allocate(String purpose) {
        String safe = purpose == null ? "op" : purpose.replaceAll("[^a-zA-Z0-9_-]", "_");
        Path dir = root.resolve(safe + "-" + UUID.randomUUID());
        try {
            Files.createDirectory(dir);
        } catch (IOException e) {
            throw new KitException("Failed to allocate kit workspace dir " + dir, e);
        }
        return dir;
    }

    /** Recursively removes a previously-allocated directory. Errors are logged, never thrown. */
    public void remove(Path dir) {
        if (dir == null) return;
        if (!dir.startsWith(root)) {
            log.warn("Refusing to remove path outside workspace: {}", dir);
            return;
        }
        if (!Files.exists(dir)) return;
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", path, e.toString());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to walk {} for removal: {}", dir, e.toString());
        }
    }

    /** {@code true} if {@code dir} appears to be empty (no entries). */
    public static boolean isEmpty(Path dir) {
        try {
            return Files.list(dir).findFirst().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code true} if {@code path} is a regular file inside the workspace. */
    public boolean contains(Path path) {
        return path != null && path.startsWith(root);
    }

    /** Visit-helper: returns a {@link BasicFileAttributes}-friendly walker hint. */
    public static boolean isFile(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return attrs.isRegularFile();
        } catch (IOException e) {
            return false;
        }
    }
}
