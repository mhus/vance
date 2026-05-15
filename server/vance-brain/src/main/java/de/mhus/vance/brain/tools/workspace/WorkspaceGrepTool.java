package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Searches scratch RootDir files for lines matching a regex —
 * project-side equivalent of {@code grep -rn}. Files larger than
 * {@link #MAX_FILE_BYTES} or non-UTF-8 are skipped silently so the
 * tool is safe across mixed binary/text trees.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceGrepTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1_000;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("pattern"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pattern", Map.of("type", "string",
                "description", "Java regular expression. Plain substrings are fine."));
        p.put("dirName", Map.of("type", "string",
                "description", "Optional RootDir name. Defaults to the current process's temp RootDir."));
        p.put("pathGlob", Map.of("type", "string",
                "description",
                        "Optional glob filter on file paths within the RootDir, "
                                + "e.g. '**/*.java'. Default: all files."));
        p.put("caseInsensitive", Map.of("type", "boolean",
                "description", "Match case-insensitively. Default: false."));
        p.put("contextBefore", Map.of("type", "integer",
                "description", "Number of lines before each match. Default: 0."));
        p.put("contextAfter", Map.of("type", "integer",
                "description", "Number of lines after each match. Default: 0."));
        p.put("limit", Map.of("type", "integer",
                "description",
                        "Cap on total match rows returned. Default: " + DEFAULT_LIMIT
                                + ", max: " + MAX_LIMIT + "."));
        return p;
    }

    private final WorkspaceService workspace;

    @Override public String name() { return "scratch_grep"; }
    @Override public String description() {
        return "Recursively grep regex patterns across files in a scratch RootDir. "
                + "Returns matching lines with file path + 1-based line number, "
                + "optionally with context lines. Binary / oversized files are skipped.";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("read-only"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String patternStr = stringOrThrow(params, "pattern");
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        String pathGlob = stringOrNull(params, "pathGlob");
        boolean ci = Boolean.TRUE.equals(params == null ? null : params.get("caseInsensitive"));
        int before = clampNonNeg(intOrNull(params, "contextBefore"));
        int after = clampNonNeg(intOrNull(params, "contextAfter"));
        int limit = clampLimit(intOrNull(params, "limit"));

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolException("Invalid regex: " + e.getMessage());
        }

        PathMatcher matcher = pathGlob == null
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + pathGlob);

        List<String> files;
        try {
            files = workspace.list(ctx.tenantId(), ctx.projectId(), dirName);
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }

        List<Map<String, Object>> matches = new ArrayList<>();
        int filesScanned = 0;
        int filesSkipped = 0;
        boolean truncated = false;
        for (String relPath : files) {
            if (matches.size() >= limit) { truncated = true; break; }
            if (matcher != null && !matcher.matches(Path.of(relPath))) continue;

            Path absolute;
            try {
                absolute = workspace.resolve(ctx.tenantId(), ctx.projectId(), dirName, relPath);
            } catch (WorkspaceException e) {
                filesSkipped++;
                continue;
            }
            try {
                if (Files.size(absolute) > MAX_FILE_BYTES) {
                    filesSkipped++;
                    continue;
                }
            } catch (IOException e) {
                filesSkipped++;
                continue;
            }

            String[] lines;
            try {
                lines = Files.readString(absolute, StandardCharsets.UTF_8).split("\\R", -1);
            } catch (IOException e) {
                // Likely binary / non-UTF8 — silently skip.
                filesSkipped++;
                continue;
            }
            filesScanned++;
            for (int i = 0; i < lines.length; i++) {
                if (!pattern.matcher(lines[i]).find()) continue;
                if (matches.size() >= limit) { truncated = true; break; }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", relPath);
                m.put("lineNumber", i + 1);
                m.put("line", lines[i]);
                if (before > 0 || after > 0) {
                    List<Map<String, Object>> ctxLines = new ArrayList<>();
                    int from = Math.max(0, i - before);
                    int to = Math.min(lines.length - 1, i + after);
                    for (int j = from; j <= to; j++) {
                        if (j == i) continue;
                        ctxLines.add(Map.of("lineNumber", j + 1, "line", lines[j]));
                    }
                    if (!ctxLines.isEmpty()) m.put("context", ctxLines);
                }
                matches.add(m);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", dirName);
        out.put("pattern", patternStr);
        out.put("filesScanned", filesScanned);
        out.put("filesSkipped", filesSkipped);
        out.put("matchCount", matches.size());
        out.put("truncated", truncated);
        out.put("matches", matches);
        return out;
    }

    private static int clampNonNeg(Integer raw) {
        return raw == null ? 0 : Math.max(0, raw);
    }

    private static int clampLimit(Integer raw) {
        if (raw == null) return DEFAULT_LIMIT;
        return Math.min(MAX_LIMIT, Math.max(1, raw));
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
