package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.ContentHashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Creates or overwrites a UTF-8 text file in a project scratch
 * RootDir. Use it for fresh files and complete rewrites — it always
 * replaces the whole content. When {@code dirName} is omitted, the
 * per-process temp RootDir is used.
 *
 * <p>Optional If-Match guard: pass the {@code contentHash} from the
 * last read as {@code expectedContentHash} and the write is refused
 * when the file changed (or vanished) meanwhile — the overwrite of a
 * concurrently modified file becomes a readable error instead of a
 * silent lost update. The result carries the {@code contentHash} of
 * the written content so a following edit can chain without
 * re-reading.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceWriteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "path",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Relative path inside the RootDir, " + "e.g. 'tool.js' or 'dir/tool.js'."),
                            "dirName",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional RootDir name. Defaults to the "
                                                    + "current process's temp RootDir."),
                            "content",
                                    Map.of(
                                            "type", "string",
                                            "description", "Full file content. Replaces any existing content."),
                            "expectedContentHash",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional If-Match guard: the contentHash "
                                                    + "from your last read of this file. The write "
                                                    + "is refused when the file changed meanwhile.")),
            "required", List.of("path", "content"));

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "work_file_write";
    }

    @Override
    public String description() {
        return "Create or overwrite a text file in a project workspace "
                + "RootDir on the brain server. Use this for scripts, "
                + "intermediate artifacts, build outputs, or anything "
                + "you want to operate on with python_run / "
                + "work_exec_run / work_file_grep next. Workspace "
                + "RootDirs are a sandbox — files here are not "
                + "searchable knowledge and may be discarded when the "
                + "project suspends. NOT for: lasting knowledge the "
                + "user will want to find/read later (use doc_write), "
                + "or files on the user's own machine (use "
                + "client_file_write). Use relative paths; parent "
                + "directories are created automatically. Promote a "
                + "workspace file to a real document with "
                + "work_file_to_doc when it earns it. Pass the contentHash "
                + "from your last work_file_read as expectedContentHash to "
                + "refuse the write when the file changed since that read.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Explicit WORK variant of file_write — targets the brain workspace regardless of the work target. Prefer file_write.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        // "workspace" tells the history-tagging hook to encode the
        // returned "path" as a WORKSPACE:<processId>/<path> resource key
        // — see HistoryTagBuilder.LABEL_WORKSPACE and
        // planning/process-history-search.md §5.1.
        return java.util.Set.of("write", "side-effect", "workspace");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = stringOrThrow(params, "path");
        String content = params == null ? null : (String) params.get("content");
        if (content == null) {
            throw new ToolException("'content' is required");
        }
        String expectedContentHash = expectedContentHashOrNull(params);
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        try {
            // If-Match before the write: nothing is overwritten on a stale
            // expectation. resolve() is the service-sanctioned path access
            // (WORK-confinement applies). A missing file counts as stale
            // too — the caller expected content that is no longer there.
            if (expectedContentHash != null) {
                Path current = workspace.resolve(ctx.tenantId(), ctx.projectId(), dirName, path);
                if (!Files.isRegularFile(current)) {
                    throw new ToolException("File changed since it was read (it no longer exists) — "
                            + "re-check with work_file_read before writing");
                }
                String actual = ContentHashes.sha256Hex(current);
                if (!expectedContentHash.equals(actual)) {
                    throw new ToolException("File changed since it was read (contentHash mismatch: expected "
                            + ContentHashes.abbreviate(expectedContentHash)
                            + ", found " + ContentHashes.abbreviate(actual)
                            + ") — read the file again before overwriting it");
                }
            }
            Path written = workspace.write(ctx.tenantId(), ctx.projectId(), dirName, path, content);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("absolutePath", written.toString());
            out.put("chars", content.length());
            out.put("contentHash", ContentHashes.sha256Hex(content));
            return out;
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
    }

    /**
     * Parses the optional If-Match guard: absent or {@code null} means
     * "no guard" (the pre-If-Match behaviour), a present value must be
     * a non-blank string — a blank one signals a confused caller and
     * is refused rather than silently ignored.
     */
    private static String expectedContentHashOrNull(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("expectedContentHash");
        if (raw == null) return null;
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'expectedContentHash' must be a non-empty string — pass the "
                    + "contentHash from your last read, or omit it");
        }
        return s;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
