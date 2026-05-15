package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Returns the first {@code head} and / or last {@code tail} lines of
 * a scratch file. Either or both may be specified; at least one is
 * required so the tool always returns a bounded slice rather than the
 * full body.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceHeadTailTool implements Tool {

    private static final int MAX_LINES = 5_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of("type", "string",
                "description", "Relative path inside the RootDir."));
        p.put("dirName", Map.of("type", "string",
                "description", "Optional RootDir name. Defaults to the current process's temp RootDir."));
        p.put("head", Map.of("type", "integer",
                "description",
                        "Lines from the top. 0 / omitted = none. Capped at " + MAX_LINES + "."));
        p.put("tail", Map.of("type", "integer",
                "description", "Lines from the bottom. Capped at " + MAX_LINES + "."));
        return p;
    }

    private final WorkspaceService workspace;

    @Override public String name() { return "scratch_head_tail"; }
    @Override public String description() {
        return "Return the first N lines (head) and / or last N lines (tail) "
                + "of a scratch file. At least one of head / tail must be > 0. "
                + "Lines are 1-based; the response carries lineNumber so the LLM "
                + "can address them again.";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("read-only"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String relPath = stringOrThrow(params, "path");
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        int head = clampLines(intOrNull(params, "head"));
        int tail = clampLines(intOrNull(params, "tail"));
        if (head == 0 && tail == 0) {
            throw new ToolException("At least one of 'head' or 'tail' must be > 0");
        }

        Path absolute;
        try {
            absolute = workspace.resolve(ctx.tenantId(), ctx.projectId(), dirName, relPath);
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }
        if (!Files.exists(absolute)) {
            throw new ToolException("Not found: " + relPath);
        }
        if (!Files.isRegularFile(absolute)) {
            throw new ToolException("Not a regular file: " + relPath);
        }

        List<String> all;
        try {
            all = Files.readAllLines(absolute, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Read failed: " + e.getMessage(), e);
        }
        int total = all.size();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", relPath);
        out.put("dirName", dirName);
        out.put("totalLines", total);
        if (head > 0) {
            int n = Math.min(head, total);
            List<Map<String, Object>> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rows.add(Map.of("lineNumber", i + 1, "line", all.get(i)));
            }
            out.put("head", rows);
        }
        if (tail > 0) {
            int n = Math.min(tail, total);
            List<Map<String, Object>> rows = new ArrayList<>(n);
            int start = total - n;
            for (int i = 0; i < n; i++) {
                rows.add(Map.of("lineNumber", start + i + 1, "line", all.get(start + i)));
            }
            out.put("tail", rows);
        }
        return out;
    }

    private static int clampLines(Integer raw) {
        if (raw == null) return 0;
        return Math.min(MAX_LINES, Math.max(0, raw));
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

    private static Integer intOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof Number n ? n.intValue() : null;
    }
}
