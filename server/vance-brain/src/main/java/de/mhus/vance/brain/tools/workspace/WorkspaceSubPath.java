package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The {@code path} parameter of the recursive {@code work_file_*} tools.
 *
 * <p>The CLIENT backends behind the same {@code file_*} wrappers take a
 * {@code path} and walk that directory; the WORK backends only ever walked
 * their whole RootDir, so a {@code file_grep(path="src/main")} silently
 * searched everything on a WORK target. This turns {@code path} into a real
 * subtree filter over the RootDir-relative paths that
 * {@code WorkspaceService.list} returns.
 *
 * <p>Everything below the subtree — glob matching, depth cap,
 * generated-content filter — judges the part <em>under</em> {@code path},
 * matching what the CLIENT backends document for their walks.
 */
final class WorkspaceSubPath {

    private WorkspaceSubPath() {}

    /**
     * Normalizes a caller-supplied {@code path} into a comparison prefix:
     * empty for "whole RootDir", otherwise slash-terminated so
     * {@code "src"} cannot match {@code "srcgen/x"}.
     */
    static String prefix(@Nullable String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty() || ".".equals(p)) return "";
        return p + "/";
    }

    /**
     * The part of {@code relPath} below {@code prefix}, or {@code null} when
     * it lies outside the subtree.
     *
     * <p>A {@code relPath} equal to the subtree itself (i.e. {@code path}
     * names a file, not a directory) also returns {@code null}: the recursive
     * tools walk directories, and letting a file-valued {@code path} through
     * would report it at depth 0 where every depth cap admits it.
     */
    static @Nullable String under(String relPath, String prefix) {
        String p = relPath.replace('\\', '/');
        if (prefix.isEmpty()) return p;
        if (!p.startsWith(prefix)) return null;
        String under = p.substring(prefix.length());
        return under.isEmpty() ? null : under;
    }

    /**
     * Rejects a {@code path} that names nothing, before the subtree filter
     * turns it into an empty result.
     *
     * <p>The filter above is a string comparison, so a mistyped
     * {@code path} matches no entry and the tool answers "0 files" /
     * "no entries" — which a model reads as "the directory is empty", not
     * as "you got the name wrong". The CLIENT halves of the same wrappers
     * fail loudly here, and {@code FileListTool}'s troubleshooting hint
     * promises the "Not a directory" message, so the WORK halves have to
     * be able to produce it.
     *
     * @param requireDirectory {@code true} for the tools that walk a
     *        subtree; {@code false} where a file-valued {@code path} is a
     *        legitimate single-file mode ({@code work_file_count})
     */
    static void requirePresent(
            WorkspaceService workspace,
            ToolInvocationContext ctx,
            String dirName,
            @Nullable String path,
            boolean requireDirectory) {
        if (path == null) return;
        Path abs;
        try {
            abs = workspace.resolve(ctx.tenantId(), ctx.projectId(), dirName, path);
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
        if (Files.isDirectory(abs)) return;
        if (!Files.exists(abs)) {
            throw new ToolException("No such path: '" + path + "' in workspace dir '"
                    + dirName + "'");
        }
        if (requireDirectory) {
            throw new ToolException("Not a directory: '" + path
                    + "' names a file — use file_read to read it.");
        }
    }
}
