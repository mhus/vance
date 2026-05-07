package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.shared.workspace.WorkspaceException;
import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Finds files in a workspace RootDir by glob pattern + size / mtime
 * filters. Pure metadata read — never opens the file body. Default
 * sort: path ascending; the {@code sortBy} parameter switches to
 * {@code mtime} descending so the LLM can ask for "what changed
 * recently" without a follow-up sort.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceFindTool implements Tool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2_000;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dirName", Map.of("type", "string",
                "description", "Optional RootDir name. Defaults to the current process's temp RootDir."));
        p.put("pathGlob", Map.of("type", "string",
                "description",
                        "Glob pattern matched against the relative path inside the "
                                + "RootDir, e.g. '**/*.md' or 'src/**/*.java'. Default: all files."));
        p.put("minSizeBytes", Map.of("type", "integer",
                "description", "Skip files smaller than this. Default: no lower bound."));
        p.put("maxSizeBytes", Map.of("type", "integer",
                "description", "Skip files larger than this. Default: no upper bound."));
        p.put("modifiedAfter", Map.of("type", "string",
                "description",
                        "Only files modified after this ISO-8601 instant "
                                + "(e.g. '2026-01-01T00:00:00Z'). Default: no lower bound."));
        p.put("modifiedBefore", Map.of("type", "string",
                "description", "Only files modified before this ISO-8601 instant. Default: no upper bound."));
        p.put("sortBy", Map.of("type", "string",
                "enum", List.of("path", "mtime", "size"),
                "description", "Sort key. 'path' (default), 'mtime' (descending), 'size' (descending)."));
        p.put("limit", Map.of("type", "integer",
                "description", "Cap on entries returned. Default: " + DEFAULT_LIMIT
                        + ", max: " + MAX_LIMIT + "."));
        return p;
    }

    private final WorkspaceService workspace;

    @Override public String name() { return "workspace_find"; }
    @Override public String description() {
        return "Find files inside a workspace RootDir by path glob, size range, and "
                + "modification-time range. Returns relative paths with size + mtime. "
                + "Sort by path (default), mtime, or size.";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("read-only"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        String pathGlob = stringOrNull(params, "pathGlob");
        Long minSize = longOrNull(params, "minSizeBytes");
        Long maxSize = longOrNull(params, "maxSizeBytes");
        Instant after = parseInstant(stringOrNull(params, "modifiedAfter"), "modifiedAfter");
        Instant before = parseInstant(stringOrNull(params, "modifiedBefore"), "modifiedBefore");
        String sortBy = stringOrNull(params, "sortBy");
        int limit = clampLimit(intOrNull(params, "limit"));

        PathMatcher matcher = pathGlob == null
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + pathGlob);

        List<String> all;
        try {
            all = workspace.list(ctx.tenantId(), ctx.projectId(), dirName);
        } catch (WorkspaceException e) {
            throw new ToolException(e.getMessage(), e);
        }

        List<Entry> entries = new ArrayList<>();
        int totalConsidered = 0;
        for (String relPath : all) {
            totalConsidered++;
            if (matcher != null && !matcher.matches(Path.of(relPath))) continue;
            Path abs;
            try {
                abs = workspace.resolve(ctx.tenantId(), ctx.projectId(), dirName, relPath);
            } catch (WorkspaceException e) {
                continue;
            }
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(abs, BasicFileAttributes.class);
            } catch (IOException e) {
                continue;
            }
            long size = attrs.size();
            Instant mtime = attrs.lastModifiedTime().toInstant();
            if (minSize != null && size < minSize) continue;
            if (maxSize != null && size > maxSize) continue;
            if (after != null && !mtime.isAfter(after)) continue;
            if (before != null && !mtime.isBefore(before)) continue;
            entries.add(new Entry(relPath, size, mtime));
        }

        switch (sortBy == null ? "path" : sortBy) {
            case "mtime" -> entries.sort((a, b) -> b.mtime.compareTo(a.mtime));
            case "size" -> entries.sort((a, b) -> Long.compare(b.size, a.size));
            default -> entries.sort((a, b) -> a.path.compareTo(b.path));
        }

        boolean truncated = entries.size() > limit;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entry e : entries.subList(0, Math.min(limit, entries.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", e.path);
            row.put("size", e.size);
            row.put("modifiedAt", e.mtime.toString());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", dirName);
        out.put("totalConsidered", totalConsidered);
        out.put("matchCount", entries.size());
        out.put("returned", rows.size());
        out.put("truncated", truncated);
        out.put("entries", rows);
        return out;
    }

    private record Entry(String path, long size, Instant mtime) {}

    private static int clampLimit(Integer raw) {
        if (raw == null) return DEFAULT_LIMIT;
        return Math.min(MAX_LIMIT, Math.max(1, raw));
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static Integer intOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof Number n ? n.intValue() : null;
    }

    private static Long longOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof Number n ? n.longValue() : null;
    }

    private static Instant parseInstant(String raw, String paramName) {
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ToolException("'" + paramName + "' must be ISO-8601 (e.g. 2026-01-01T00:00:00Z); got: " + raw);
        }
    }
}
