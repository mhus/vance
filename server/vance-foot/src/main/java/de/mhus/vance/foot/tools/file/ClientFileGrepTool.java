package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * {@code grep -rn} on the foot host. Walks a directory tree (default:
 * the working dir) and returns regex matches as
 * {@code (relativePath, lineNumber, line)} rows. Symlinks are not
 * followed; binary / non-UTF-8 files and oversized files
 * ({@code > 2 MiB}) are skipped silently.
 */
@Component
public class ClientFileGrepTool implements ClientTool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1_000;
    private static final int DEFAULT_MAX_DEPTH = 12;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /**
     * Hard cap on a single match's {@code line} (and each context-line)
     * payload. Generated/minified files routinely contain multi-MB lines
     * — without this cap a single grep match could blow past the
     * WebSocket {@code maxTextMessageBufferSize} on the brain (close
     * code 1009). 600 chars is wide enough to read a normal line of
     * code with surrounding context and narrow enough that 200 matches
     * stay well under 1 MiB total.
     */
    private static final int MAX_LINE_CHARS = 600;
    /** Truncation marker appended when a line exceeds {@link #MAX_LINE_CHARS}. */
    private static final String TRUNCATE_MARK = "…[truncated]";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("pattern"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pattern", Map.of("type", "string",
                "description", "Java regular expression. Plain substrings are fine."));
        p.put("path", Map.of("type", "string",
                "description",
                        "Directory to search (recursive). Default: current working directory."
                                + " Supports a leading '~/' for the user's home."));
        p.put("pathGlob", Map.of("type", "string",
                "description",
                        "Optional glob filter on file paths relative to 'path', "
                                + "e.g. '**/*.java'. Default: all files."));
        p.put("caseInsensitive", Map.of("type", "boolean",
                "description", "Match case-insensitively. Default: false."));
        p.put("contextBefore", Map.of("type", "integer",
                "description", "Number of lines before each match. Default: 0."));
        p.put("contextAfter", Map.of("type", "integer",
                "description", "Number of lines after each match. Default: 0."));
        p.put("maxDepth", Map.of("type", "integer",
                "description",
                        "Recursion depth cap. Default: " + DEFAULT_MAX_DEPTH
                                + ". Use 1 to scan a flat directory."));
        p.put("limit", Map.of("type", "integer",
                "description", "Cap on total match rows. Default: " + DEFAULT_LIMIT
                        + ", max: " + MAX_LIMIT + "."));
        return p;
    }

    @Override public String name() { return "client_file_grep"; }
    @Override public String description() {
        return "Recursively grep regex patterns across files on the user's machine. "
                + "Returns matching lines with relative path + 1-based line number, "
                + "optionally with context. Binary / oversized files are skipped.";
    }
    @Override public boolean primary() { return true; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Requires CLIENT target. No matches = pattern/path; timeout = path too broad, narrow with pathGlob/path.";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem", "client", "search");
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        String patternStr = stringOrThrow(params, "pattern");
        String pathRaw = stringOrNull(params, "path");
        String pathGlob = stringOrNull(params, "pathGlob");
        boolean ci = Boolean.TRUE.equals(params == null ? null : params.get("caseInsensitive"));
        int before = clampNonNeg(intOrNull(params, "contextBefore"));
        int after = clampNonNeg(intOrNull(params, "contextAfter"));
        int maxDepth = clampDepth(intOrNull(params, "maxDepth"));
        int limit = clampLimit(intOrNull(params, "limit"));

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex: " + e.getMessage());
        }

        Path root = pathRaw == null ? Path.of(".") : ClientFilePaths.resolve(pathRaw);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root.toAbsolutePath());
        }
        PathMatcher matcher = GlobMatchers.buildGlobMatcher(pathGlob);

        List<Map<String, Object>> matches = new ArrayList<>();
        int filesScanned = 0;
        int filesSkipped = 0;
        boolean truncated = false;
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (matches.size() >= limit) { truncated = true; break; }
                if (!Files.isRegularFile(file)) continue;
                Path rel = root.relativize(file);
                if (matcher != null && !matcher.matches(rel)) continue;
                long size;
                try { size = Files.size(file); } catch (IOException ignored) {
                    filesSkipped++; continue;
                }
                if (size > MAX_FILE_BYTES) { filesSkipped++; continue; }
                String[] lines;
                try {
                    lines = Files.readString(file, StandardCharsets.UTF_8).split("\\R", -1);
                } catch (IOException e) {
                    filesSkipped++; continue;
                }
                filesScanned++;
                for (int i = 0; i < lines.length; i++) {
                    if (!pattern.matcher(lines[i]).find()) continue;
                    if (matches.size() >= limit) { truncated = true; break; }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", rel.toString());
                    m.put("lineNumber", i + 1);
                    m.put("line", clipLine(lines[i]));
                    if (before > 0 || after > 0) {
                        List<Map<String, Object>> ctxLines = new ArrayList<>();
                        int from = Math.max(0, i - before);
                        int to = Math.min(lines.length - 1, i + after);
                        for (int j = from; j <= to; j++) {
                            if (j == i) continue;
                            ctxLines.add(Map.of(
                                    "lineNumber", j + 1,
                                    "line", clipLine(lines[j])));
                        }
                        if (!ctxLines.isEmpty()) m.put("context", ctxLines);
                    }
                    matches.add(m);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Walk failed: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", root.toAbsolutePath().toString());
        out.put("pattern", patternStr);
        out.put("filesScanned", filesScanned);
        out.put("filesSkipped", filesSkipped);
        out.put("matchCount", matches.size());
        out.put("truncated", truncated);
        out.put("matches", matches);
        return out;
    }

    private static String clipLine(String line) {
        if (line == null || line.length() <= MAX_LINE_CHARS) return line;
        return line.substring(0, MAX_LINE_CHARS) + TRUNCATE_MARK;
    }

    private static int clampNonNeg(Integer raw) { return raw == null ? 0 : Math.max(0, raw); }

    private static int clampLimit(Integer raw) {
        if (raw == null) return DEFAULT_LIMIT;
        return Math.min(MAX_LIMIT, Math.max(1, raw));
    }

    private static int clampDepth(Integer raw) {
        if (raw == null) return DEFAULT_MAX_DEPTH;
        return Math.min(50, Math.max(1, raw));
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' is required and must be a non-empty string");
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
