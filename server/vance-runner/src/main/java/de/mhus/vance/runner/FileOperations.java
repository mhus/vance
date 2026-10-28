package de.mhus.vance.runner;

import de.mhus.vance.exec.RootDirFiles;
import de.mhus.vance.exec.RootDirHandle;
import de.mhus.vance.exec.RootPaths;
import de.mhus.vance.runner.RunnerOperation.Call;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.RunnerOperation.OperationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * {@code file.read}, {@code file.write}, {@code file.edit}, {@code file.list},
 * {@code file.delete} — text files inside a RootDir. Paths are resolved by
 * {@link RootPaths}, the same containment the brain's workspace uses; results carry
 * the field names of the {@code work_file_*} tools so the brain can hand them on.
 */
final class FileOperations {

    private static final int DEFAULT_READ_CHARS = 200_000;

    private FileOperations() {}

    static List<RunnerOperation> all(RunnerWorkspace workspace) {
        return List.of(read(workspace), write(workspace), edit(workspace), list(workspace), delete(workspace));
    }

    private static RunnerOperation read(RunnerWorkspace workspace) {
        return RootDirOperations.operation("file.read", call -> {
            OperationArgs args = new OperationArgs(call.args());
            String dirName = args.rootDir(call.claims());
            String path = args.text("path");
            Path resolved = resolve(workspace, call, dirName, path);
            int maxChars = args.integer("maxChars", DEFAULT_READ_CHARS);
            int startLine = args.integer("startLine", 0);
            RootDirFiles.ReadResult result = startLine > 0
                    ? RootDirFiles.readLines(resolved, path, maxChars, startLine, args.integer("maxLines", 2000))
                    : RootDirFiles.read(resolved, path, maxChars);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("content", result.text());
            out.put("truncated", result.truncated());
            out.put("totalChars", result.totalChars());
            if (!result.truncated() && startLine <= 0) {
                out.put("contentHash", OperationArgs.sha256Hex(result.text()));
            }
            return OperationResult.of(out);
        });
    }

    private static RunnerOperation write(RunnerWorkspace workspace) {
        return RootDirOperations.operation("file.write", call -> {
            OperationArgs args = new OperationArgs(call.args());
            String dirName = args.rootDir(call.claims());
            String path = args.text("path");
            String content = args.optionalText("content");
            if (content == null) {
                throw new OperationException("bad-args", "'content' is required");
            }
            String expectedHash = args.optionalText("expectedContentHash");
            if (expectedHash != null && expectedHash.isBlank()) {
                throw new OperationException(
                        "bad-args",
                        "'expectedContentHash' must be a non-empty string — pass the contentHash of your last read, or omit it");
            }
            Path resolved = resolve(workspace, call, dirName, path);
            // The precondition before the write; a missing file counts as stale too.
            if (expectedHash != null) {
                if (!Files.isRegularFile(resolved)) {
                    throw new OperationException(
                            "stale-content",
                            "File changed since it was read (it no longer exists) — re-check with a read before writing");
                }
                String actual = OperationArgs.sha256Hex(
                        RootDirFiles.read(resolved, path, Integer.MAX_VALUE).text());
                if (!expectedHash.equals(actual)) {
                    throw new OperationException(
                            "stale-content",
                            "File changed since it was read (contentHash mismatch) — read the file again before overwriting it");
                }
            }
            RootDirFiles.write(resolved, content);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("chars", content.length());
            out.put("contentHash", OperationArgs.sha256Hex(content));
            return OperationResult.of(out);
        });
    }

    private static RunnerOperation edit(RunnerWorkspace workspace) {
        return RootDirOperations.operation("file.edit", call -> {
            OperationArgs args = new OperationArgs(call.args());
            String dirName = args.rootDir(call.claims());
            String path = args.text("path");
            String oldText = args.text("oldText");
            String newText = args.optionalText("newText");
            if (newText == null) {
                throw new OperationException("bad-args", "'newText' is required");
            }
            String expectedHash = args.optionalText("expectedContentHash");
            if (expectedHash != null && expectedHash.isBlank()) {
                throw new OperationException(
                        "bad-args",
                        "'expectedContentHash' must be a non-empty string — pass the contentHash of your last read, or omit it");
            }
            Path resolved = resolve(workspace, call, dirName, path);
            RootDirFiles.ReadResult current = RootDirFiles.read(resolved, path, DEFAULT_READ_CHARS);
            if (current.truncated()) {
                throw new OperationException(
                        "too-large",
                        "File too large to edit safely (truncated at " + DEFAULT_READ_CHARS
                                + " chars). Use file.write to rewrite or split the change.");
            }
            String content = current.text();
            // The precondition first: a stale file means "read again", not "add more context".
            if (expectedHash != null && !expectedHash.equals(OperationArgs.sha256Hex(content))) {
                throw new OperationException(
                        "stale-content",
                        "File changed since it was read (contentHash mismatch) — read the file again and retry with the current contentHash");
            }
            int first = content.indexOf(oldText);
            if (first < 0) {
                throw new OperationException("not-found", "oldText not found in " + path);
            }
            if (content.indexOf(oldText, first + oldText.length()) >= 0) {
                throw new OperationException("ambiguous", "oldText appears multiple times — add context until unique");
            }
            String updated = content.substring(0, first) + newText + content.substring(first + oldText.length());
            RootDirFiles.write(resolved, updated);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("replaced", 1);
            out.put("totalChars", updated.length());
            out.put("contentHash", OperationArgs.sha256Hex(updated));
            return OperationResult.of(out);
        });
    }

    private static RunnerOperation list(RunnerWorkspace workspace) {
        return RootDirOperations.operation("file.list", call -> {
            OperationArgs args = new OperationArgs(call.args());
            String dirName = args.rootDir(call.claims());
            String subPath = args.optionalText("path");
            Path base = handle(workspace, call, dirName).getPath();
            String prefix = prefix(subPath);
            if (!prefix.isEmpty() && !Files.isDirectory(RootPaths.resolveWithin(base, subPath))) {
                throw new OperationException("not-found", "No such directory: " + subPath);
            }
            // RootDirFiles.list is recursive and file-only: fold it back to one level.
            Set<String> entries = new TreeSet<>();
            for (String relative : RootDirFiles.list(base)) {
                if (!relative.startsWith(prefix)) {
                    continue;
                }
                String under = relative.substring(prefix.length());
                int slash = under.indexOf('/');
                entries.add(slash < 0 ? under : under.substring(0, slash) + "/");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dirName", dirName);
            out.put("path", subPath == null || subPath.isBlank() ? "." : subPath);
            out.put("entries", List.copyOf(entries));
            out.put("count", entries.size());
            return OperationResult.of(out);
        });
    }

    /** {@code "a/b"} → {@code "a/b/"}; blank, {@code "."} and {@code "/"} → the root. */
    private static String prefix(@Nullable String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.strip();
        while (trimmed.startsWith("./")) {
            trimmed = trimmed.substring(2);
        }
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("/")) {
            return "";
        }
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private static RunnerOperation delete(RunnerWorkspace workspace) {
        return RootDirOperations.operation("file.delete", call -> {
            OperationArgs args = new OperationArgs(call.args());
            String dirName = args.rootDir(call.claims());
            String path = args.text("path");
            boolean deleted = RootDirFiles.delete(resolve(workspace, call, dirName, path), path);
            return OperationResult.of(Map.of("path", path, "dirName", dirName, "deleted", deleted));
        });
    }

    private static RootDirHandle handle(RunnerWorkspace workspace, Call call, String dirName)
            throws OperationException {
        return workspace
                .store()
                .getRootDir(call.claims().tenantId(), call.claims().projectId(), dirName)
                .orElseThrow(() -> new OperationException("unknown-rootdir", "Unknown RootDir: " + dirName));
    }

    private static Path resolve(RunnerWorkspace workspace, Call call, String dirName, String path)
            throws OperationException {
        return RootPaths.resolveWithin(handle(workspace, call, dirName).getPath(), path);
    }
}
