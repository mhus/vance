package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.ContentHashes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a UTF-8 text file from a project workspace RootDir. Truncates
 * at {@link WorkspaceProperties#getDefaultReadCharCap()} by default.
 * When {@code dirName} is omitted, the per-process temp RootDir is
 * used.
 *
 * <p>Every result carries {@code contentHash}: SHA-256 of the
 * <em>whole file</em>, independent of the served window or cap. Pass it
 * as {@code expectedContentHash} to {@code work_file_edit} /
 * {@code work_file_write} to make the change fail when the file
 * changed since this read.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceReadTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "path",
                                    Map.of(
                                            "type", "string",
                                            "description", "Relative path inside the RootDir."),
                            "dirName",
                                    Map.of(
                                            "type",
                                            "string",
                                            "description",
                                            "Optional RootDir name. Defaults to the "
                                                    + "current process's temp RootDir."),
                            "maxChars",
                                    Map.of(
                                            "type",
                                            "integer",
                                            "description",
                                            "Maximum characters to return. 0 or negative "
                                                    + "means use the server default cap."),
                            "startLine",
                                    Map.of(
                                            "type",
                                            "integer",
                                            "description",
                                            "1-based first line to return. Omit to start " + "at the beginning."),
                            "maxLines",
                                    Map.of(
                                            "type",
                                            "integer",
                                            "description",
                                            "Maximum number of lines to return. Combine "
                                                    + "with startLine to page through a "
                                                    + "file that exceeds the char cap.")),
            "required", List.of("path"));

    private final WorkspaceService workspace;
    private final WorkspaceProperties properties;

    @Override
    public String name() {
        return "work_file_read";
    }

    @Override
    public String description() {
        return "Read a text file from a project workspace RootDir. Returns "
                + "the file content; if longer than the cap, only the prefix "
                + "is returned and 'truncated' is true.";
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
        return "Explicit WORK variant of file_read — targets the brain workspace regardless of the work target. Prefer file_read.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("read-only", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String path = stringOrThrow(params, "path");
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        int cap = properties.getDefaultReadCharCap();
        Object rawMax = params == null ? null : params.get("maxChars");
        if (rawMax instanceof Number n && n.intValue() > 0) {
            cap = n.intValue();
        }
        int startLine = intOrZero(params, "startLine");
        int maxLines = intOrZero(params, "maxLines");
        try {
            // Line window when asked for, whole file (capped) otherwise —
            // paging is the only way to reach the middle of a file that is
            // larger than the cap.
            WorkspaceService.ReadResult r = startLine > 0 || maxLines > 0
                    ? workspace.readLines(ctx.tenantId(), ctx.projectId(), dirName, path, cap, startLine, maxLines)
                    : workspace.read(ctx.tenantId(), ctx.projectId(), dirName, path, cap);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("dirName", dirName);
            out.put("content", r.text());
            out.put("truncated", r.truncated());
            out.put("totalChars", r.totalChars());
            // Whole-file hash for the edit/write If-Match guard — the same
            // value no matter which window this call served. resolve() is
            // the service-sanctioned path access (WORK-confinement applies),
            // the same surface the grep/find tools read through.
            out.put(
                    "contentHash",
                    ContentHashes.sha256Hex(workspace.resolve(ctx.tenantId(), ctx.projectId(), dirName, path)));
            return out;
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        } catch (java.io.UncheckedIOException e) {
            throw new ToolException("Read failed: " + e.getMessage(), e);
        }
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

    /** Missing / non-numeric / negative all mean "not requested". */
    private static int intOrZero(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof Number n && n.intValue() > 0 ? n.intValue() : 0;
    }
}
