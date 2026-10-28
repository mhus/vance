package de.mhus.vance.exec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * File operations inside a RootDir, on paths that {@link RootPaths#resolveWithin}
 * already confined. Every failure is a {@link RootDirException}; the messages are
 * part of the contract (the tools show them to the agent).
 *
 * <p>The relative path some methods take is only for the error message — the
 * caller resolved it before and passes the result.
 */
public final class RootDirFiles {

    private RootDirFiles() {}

    /** Text read result; {@code totalChars} counts the selected window, not the file. */
    public record ReadResult(String text, boolean truncated, int totalChars) {}

    /** Writes text at {@code resolved}, creating parents and overwriting. */
    public static Path write(Path resolved, @Nullable String content) {
        try {
            createParents(resolved);
            Files.writeString(
                    resolved,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return resolved;
        } catch (IOException e) {
            throw new RootDirException("Write failed: " + e.getMessage(), e);
        }
    }

    /**
     * Writes a stream's raw bytes at {@code resolved}, creating parents and
     * overwriting. Byte-exact counterpart of {@link #readBytes} — no charset
     * round-trip, so binary payloads (parquet, zip, images) survive the copy.
     */
    public static Path writeStream(Path resolved, InputStream in) {
        try {
            createParents(resolved);
            Files.copy(in, resolved, StandardCopyOption.REPLACE_EXISTING);
            return resolved;
        } catch (IOException e) {
            throw new RootDirException("Write failed: " + e.getMessage(), e);
        }
    }

    /** Reads UTF-8 text; truncates to {@code maxChars} ({@code <= 0} = unlimited). */
    public static ReadResult read(Path resolved, String relativePath, int maxChars) {
        requireRegularFileExists(resolved, relativePath);
        try {
            String full = Files.readString(resolved, StandardCharsets.UTF_8);
            if (maxChars > 0 && full.length() > maxChars) {
                return new ReadResult(full.substring(0, maxChars), true, full.length());
            }
            return new ReadResult(full, false, full.length());
        } catch (IOException e) {
            throw new RootDirException("Read failed: " + e.getMessage(), e);
        }
    }

    /**
     * Line-window variant of {@link #read}: the lines from {@code startLine}
     * (1-based, inclusive) up to {@code maxLines} of them, then the
     * {@code maxChars} cap on that window. {@code startLine <= 0} starts at the
     * beginning, {@code maxLines <= 0} runs to the end of the file.
     *
     * <p>Exists so a caller can page through a file larger than any sane character
     * cap; without it the only way to see the middle of a big file is to shell out.
     *
     * <p>{@code UncheckedIOException} is caught alongside {@code IOException}
     * because {@link Files#lines} reports a decoding failure while the stream is
     * being <em>consumed</em>, wrapped — a Latin-1 or binary file would otherwise
     * leave through a different exception type than the same file read through
     * {@link #read}.
     */
    public static ReadResult readLines(Path resolved, String relativePath, int maxChars, int startLine, int maxLines) {
        requireRegularFileExists(resolved, relativePath);
        long skip = startLine > 1 ? startLine - 1 : 0;
        long limit = maxLines > 0 ? maxLines : Long.MAX_VALUE;
        try (Stream<String> lines = Files.lines(resolved, StandardCharsets.UTF_8)) {
            String window = lines.skip(skip).limit(limit).collect(Collectors.joining("\n"));
            if (maxChars > 0 && window.length() > maxChars) {
                return new ReadResult(window.substring(0, maxChars), true, window.length());
            }
            return new ReadResult(window, false, window.length());
        } catch (IOException e) {
            throw new RootDirException("Read failed: " + e.getMessage(), e);
        } catch (UncheckedIOException e) {
            IOException cause = e.getCause();
            throw new RootDirException("Read failed: " + (cause == null ? e.getMessage() : cause.getMessage()), e);
        }
    }

    /** Recursive file list below {@code base}, paths relative to it, sorted. */
    public static List<String> list(Path base) {
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(Files::isRegularFile)
                    .map(base::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RootDirException("List failed: " + e.getMessage(), e);
        }
    }

    /** Deletes a single file. Returns false if it wasn't there. */
    public static boolean delete(Path resolved, String relativePath) {
        try {
            if (Files.isDirectory(resolved)) {
                throw new RootDirException("Refusing to delete a directory: " + relativePath);
            }
            return Files.deleteIfExists(resolved);
        } catch (IOException e) {
            throw new RootDirException("Delete failed: " + e.getMessage(), e);
        }
    }

    /** Asserts {@code resolved} is a regular file and returns it, for native I/O. */
    public static Path requireRegularFile(Path resolved, String relativePath) {
        if (!Files.isRegularFile(resolved)) {
            throw new RootDirException("Not a regular file: " + relativePath);
        }
        return resolved;
    }

    /**
     * Reads a file's full bytes, refusing anything larger than {@code maxBytes}
     * ({@code <= 0} = unlimited) with {@link RootDirFileSizeExceededException}.
     */
    public static byte[] readBytes(Path resolved, String relativePath, long maxBytes) {
        requireRegularFile(resolved, relativePath);
        long size;
        try {
            size = Files.size(resolved);
        } catch (IOException e) {
            throw new RootDirException("Stat failed: " + e.getMessage(), e);
        }
        if (maxBytes > 0 && size > maxBytes) {
            throw new RootDirFileSizeExceededException(
                    "File '" + relativePath + "' is " + size + " bytes; exceeds limit of " + maxBytes, size, maxBytes);
        }
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new RootDirException("Read failed: " + e.getMessage(), e);
        }
    }

    /**
     * Directory node with its children listed {@code remainingDepth} levels deep
     * ({@code 0} = no children). Symlinks are skipped entirely: the type checks
     * below FOLLOW links, so a symlink inside the RootDir pointing at an external
     * directory would be classified as a directory and recursed — leaking the host
     * tree's structure, names, sizes and mtimes. (File <em>content</em> is
     * separately protected by {@link RootPaths}.) Sockets and FIFOs fall through
     * the directory/file tests and are skipped as well.
     */
    public static TreeNode dirNode(String name, String path, Path dir, int remainingDepth) {
        Instant mtime = readMtime(dir);
        List<TreeNode> children = null;
        if (remainingDepth > 0) {
            children = new ArrayList<>();
            try (Stream<Path> entries = Files.list(dir)) {
                List<Path> sorted = entries.sorted(
                                Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                for (Path entry : sorted) {
                    if (Files.isSymbolicLink(entry)) {
                        continue;
                    }
                    String childName = entry.getFileName().toString();
                    String childPath = path.isEmpty() ? childName : path + "/" + childName;
                    if (Files.isDirectory(entry)) {
                        children.add(dirNode(childName, childPath, entry, remainingDepth - 1));
                    } else if (Files.isRegularFile(entry)) {
                        children.add(fileNode(childName, childPath, entry));
                    }
                }
            } catch (IOException e) {
                throw new RootDirException("List failed for " + path + ": " + e.getMessage(), e);
            }
        }
        return new TreeNode(name, path, true, 0L, mtime, children);
    }

    /** File node with size and mtime. */
    public static TreeNode fileNode(String name, String path, Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return new TreeNode(
                    name, path, false, attrs.size(), attrs.lastModifiedTime().toInstant(), null);
        } catch (IOException e) {
            throw new RootDirException("Stat failed for " + path + ": " + e.getMessage(), e);
        }
    }

    private static void requireRegularFileExists(Path resolved, String relativePath) {
        if (!Files.exists(resolved)) {
            throw new RootDirException("Not found: " + relativePath);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new RootDirException("Not a regular file: " + relativePath);
        }
    }

    private static void createParents(Path resolved) throws IOException {
        if (resolved.getParent() != null) {
            Files.createDirectories(resolved.getParent());
        }
    }

    private static @Nullable Instant readMtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return null;
        }
    }
}
