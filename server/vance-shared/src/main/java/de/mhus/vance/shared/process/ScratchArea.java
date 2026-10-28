package de.mhus.vance.shared.process;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Where the mechanics behind the {@link ProcessLauncher} keep their short-lived
 * files ({@code planning/multi-tenant-isolation.md} §4.4a): ffmpeg's input and
 * output, whisper's audio, yt-dlp's download. The bytes come from documents or a
 * URL, never from a workspace, and the agent never sees these files.
 *
 * <p>Each call gets a {@link Scratch} directory of its own under the root and
 * declares exactly that directory as writable for its child; closing it removes
 * everything inside. {@link #sweep} clears what a crashed brain left behind.
 *
 * <p>Plain Java on purpose — the runner reuses it (phase 2f).
 */
@Slf4j
public final class ScratchArea {

    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private final Path root;

    public ScratchArea(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /**
     * A fresh, empty directory {@code <root>/<prefix>-<uuid>}.
     *
     * @param prefix lowercase label naming the user ({@code ffmpeg}, {@code whisper})
     */
    public Scratch open(String prefix) throws IOException {
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid scratch prefix: " + prefix);
        }
        Files.createDirectories(root);
        return new Scratch(Files.createDirectory(root.resolve(prefix + "-" + UUID.randomUUID())));
    }

    /**
     * Removes entries of the root last modified before {@code now - minAge}. The age
     * guard keeps a second brain sharing the directory (local dev) from losing the
     * files of a call in flight.
     *
     * @return number of entries removed
     */
    public int sweep(Duration minAge) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        FileTime cutoff = FileTime.from(Instant.now().minus(minAge));
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                try {
                    if (Files.getLastModifiedTime(entry).compareTo(cutoff) < 0) {
                        deleteRecursively(entry);
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("Could not remove scratch leftover {}: {}", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not scan the scratch directory {}: {}", root, e.getMessage());
        }
        return removed;
    }

    /** One call's directory. Closing deletes it with everything inside. */
    public static final class Scratch implements AutoCloseable {

        private final Path dir;

        private Scratch(Path dir) {
            this.dir = dir;
        }

        public Path dir() {
            return dir;
        }

        /** A path for {@code name} inside this directory; nothing is created. */
        public Path file(String name) {
            Path file = dir.resolve(name).normalize();
            if (!dir.equals(file.getParent())) {
                throw new IllegalArgumentException("Scratch file name must be a plain name: " + name);
            }
            return file;
        }

        /** Writes {@code content} to {@code name} inside this directory. */
        public Path write(String name, byte[] content) throws IOException {
            return Files.write(file(name), content);
        }

        @Override
        public void close() {
            try {
                deleteRecursively(dir);
            } catch (IOException e) {
                log.warn("Could not remove scratch directory {}: {}", dir, e.getMessage());
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
