package de.mhus.vance.brain.kit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Folder-backed {@link WriteableTarget}. Writes the kit tree directly into
 * the configured directory; no git operations involved. Used for
 * {@code file://} and absolute-path export targets.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Snapshot a project's kit to a NAS / USB stick / shared drive.</li>
 *   <li>ai-test scenarios where running a real git remote is overkill.</li>
 *   <li>Round-tripping kits through a flat directory for inspection.</li>
 * </ul>
 *
 * <p>{@link #commitAndPublish} is a no-op; files are already on disk by
 * the time it's called.
 */
final class FolderWriteableTarget implements WriteableTarget {

    private final Path directory;

    FolderWriteableTarget(Path directory) {
        if (!Files.isDirectory(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new KitException("failed to create export directory " + directory, e);
            }
        }
        this.directory = directory;
    }

    @Override
    public Path workTree() {
        return directory;
    }

    @Override
    public @Nullable String token() {
        return null;
    }

    @Override
    public Optional<String> commitAndPublish(String commitMessage, @Nullable String actor) {
        // Nothing to commit — files are already on disk.
        return Optional.empty();
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
