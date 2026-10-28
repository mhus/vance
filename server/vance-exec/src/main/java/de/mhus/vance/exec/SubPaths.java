package de.mhus.vance.exec;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The {@code path} parameter of the recursive file tools ({@code file_find},
 * {@code file_grep}, {@code file_count}, {@code file_list}).
 *
 * <p>The CLIENT backends take a {@code path} and walk that directory; a RootDir
 * only ever hands out the list of all its files, so {@code path} has to become a
 * subtree filter over the RootDir-relative paths — otherwise
 * {@code file_grep(path="src/main")} would silently search everything.
 *
 * <p>Everything below the subtree — glob matching, depth cap, generated-content
 * filter — judges the part <em>under</em> {@code path}, matching what the CLIENT
 * backends document for their walks.
 */
public final class SubPaths {

    private SubPaths() {}

    /**
     * Normalizes a caller-supplied {@code path} into a comparison prefix: empty for
     * "whole RootDir", otherwise slash-terminated so {@code "src"} cannot match
     * {@code "srcgen/x"}.
     */
    public static String prefix(@Nullable String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty() || ".".equals(p)) {
            return "";
        }
        return p + "/";
    }

    /**
     * The part of {@code relPath} below {@code prefix}, or {@code null} when it lies
     * outside the subtree.
     *
     * <p>A {@code relPath} equal to the subtree itself (i.e. {@code path} names a
     * file, not a directory) also returns {@code null}: the recursive tools walk
     * directories, and letting a file-valued {@code path} through would report it at
     * depth 0 where every depth cap admits it.
     */
    public static @Nullable String under(String relPath, String prefix) {
        String p = relPath.replace('\\', '/');
        if (prefix.isEmpty()) {
            return p;
        }
        if (!p.startsWith(prefix)) {
            return null;
        }
        String under = p.substring(prefix.length());
        return under.isEmpty() ? null : under;
    }

    /**
     * Rejects a {@code path} that names nothing, before the subtree filter turns it
     * into an empty result: the filter is a string comparison, so a mistyped
     * {@code path} matches no entry and the tool would answer "0 files" — which a
     * model reads as "the directory is empty", not as "you got the name wrong".
     *
     * @param requireDirectory {@code true} for the tools that walk a subtree;
     *     {@code false} where a file-valued {@code path} is a legitimate single-file
     *     mode ({@code file_count})
     * @throws RootDirException when the path is missing, or a file where a directory is required
     */
    public static void requirePresent(Path base, @Nullable String path, String dirName, boolean requireDirectory) {
        if (path == null) {
            return;
        }
        Path abs = RootPaths.resolveWithin(base, path);
        if (Files.isDirectory(abs)) {
            return;
        }
        if (!Files.exists(abs)) {
            throw new RootDirException("No such path: '" + path + "' in workspace dir '" + dirName + "'");
        }
        if (requireDirectory) {
            throw new RootDirException("Not a directory: '" + path + "' names a file — use file_read to read it.");
        }
    }
}
