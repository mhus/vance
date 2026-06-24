package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import java.io.IOException;
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
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * {@code find} on the foot host. Walks a directory tree and returns
 * relative paths filtered by glob, size, and modification-time
 * windows. Pure metadata read — never opens the file body. Default
 * sort: path ascending; {@code mtime} descending is one extra
 * argument away.
 */
@Component
public class ClientFileFindTool implements ClientTool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2_000;
    private static final int DEFAULT_MAX_DEPTH = 12;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of("type", "string",
                "description", "Directory to walk. Default: current working directory."));
        p.put("pathGlob", Map.of("type", "string",
                "description",
                        "Glob pattern matched against the relative path under "
                                + "'path', e.g. '**/*.md'. Default: all files."));
        p.put("minSizeBytes", Map.of("type", "integer",
                "description", "Skip files smaller than this. Default: no lower bound."));
        p.put("maxSizeBytes", Map.of("type", "integer",
                "description", "Skip files larger than this. Default: no upper bound."));
        p.put("modifiedAfter", Map.of("type", "string",
                "description",
                        "ISO-8601 instant — only files modified strictly after. "
                                + "Default: no lower bound."));
        p.put("modifiedBefore", Map.of("type", "string",
                "description", "ISO-8601 instant — only files modified strictly before. Default: no upper bound."));
        p.put("sortBy", Map.of("type", "string",
                "enum", List.of("path", "mtime", "size"),
                "description", "Sort key. 'path' (default), 'mtime' (descending), 'size' (descending)."));
        p.put("maxDepth", Map.of("type", "integer",
                "description",
                        "Recursion depth cap. Default: " + DEFAULT_MAX_DEPTH
                                + ". Use 1 to scan a flat directory."));
        p.put("limit", Map.of("type", "integer",
                "description", "Cap on entries returned. Default: " + DEFAULT_LIMIT
                        + ", max: " + MAX_LIMIT + "."));
        return p;
    }

    @Override public String name() { return "client_file_find"; }
    @Override public String description() {
        return "Find files on the user's machine by path glob, size range, and "
                + "modification-time range. Returns relative paths with size + mtime. "
                + "Recursive walk under 'path'.";
    }
    @Override public boolean primary() { return true; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Requires CLIENT target. No results = check path/pathGlob; timeout = scope too broad, narrow path.";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem", "client", "search");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        String pathRaw = stringOrNull(params, "path");
        String pathGlob = stringOrNull(params, "pathGlob");
        Long minSize = longOrNull(params, "minSizeBytes");
        Long maxSize = longOrNull(params, "maxSizeBytes");
        Instant after = parseInstant(stringOrNull(params, "modifiedAfter"), "modifiedAfter");
        Instant before = parseInstant(stringOrNull(params, "modifiedBefore"), "modifiedBefore");
        String sortBy = stringOrNull(params, "sortBy");
        int maxDepth = clampDepth(intOrNull(params, "maxDepth"));
        int limit = clampLimit(intOrNull(params, "limit"));

        Path root = pathRaw == null ? Path.of(".") : ClientFilePaths.resolve(pathRaw);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root.toAbsolutePath());
        }
        PathMatcher matcher = GlobMatchers.buildGlobMatcher(pathGlob);

        List<Entry> entries = new ArrayList<>();
        int totalConsidered = 0;
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                totalConsidered++;
                Path rel = root.relativize(file);
                if (matcher != null && !matcher.matches(rel)) continue;
                BasicFileAttributes attrs;
                try { attrs = Files.readAttributes(file, BasicFileAttributes.class); }
                catch (IOException ignored) { continue; }
                long size = attrs.size();
                Instant mtime = attrs.lastModifiedTime().toInstant();
                if (minSize != null && size < minSize) continue;
                if (maxSize != null && size > maxSize) continue;
                if (after != null && !mtime.isAfter(after)) continue;
                if (before != null && !mtime.isBefore(before)) continue;
                entries.add(new Entry(rel.toString(), size, mtime));
            }
        } catch (IOException e) {
            throw new RuntimeException("Walk failed: " + e.getMessage(), e);
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
        out.put("path", root.toAbsolutePath().toString());
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

    private static int clampDepth(Integer raw) {
        if (raw == null) return DEFAULT_MAX_DEPTH;
        return Math.min(50, Math.max(1, raw));
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
            throw new IllegalArgumentException(
                    "'" + paramName + "' must be ISO-8601 (e.g. 2026-01-01T00:00:00Z); got: " + raw);
        }
    }
}
