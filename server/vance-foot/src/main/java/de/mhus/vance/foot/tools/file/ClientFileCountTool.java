package de.mhus.vance.foot.tools.file;

import de.mhus.vance.foot.tools.ClientTool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * {@code wc}-style counter on the foot host. Counts lines /
 * characters / bytes for a single file or — when {@code path} resolves
 * to a directory — across every file in the tree matching an optional
 * glob. With a regex, the line-count switches to "lines that match"
 * so the LLM can answer questions like "how many TODO markers are
 * left in {@code src/}".
 */
@Component
public class ClientFileCountTool implements ClientTool {

    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final int DEFAULT_MAX_DEPTH = 12;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of("type", "string",
                "description",
                        "File or directory on the foot host. Directories are walked "
                                + "recursively. Supports a leading '~/' for home."));
        p.put("pathGlob", Map.of("type", "string",
                "description",
                        "Glob filter on file paths under 'path' (directories only). "
                                + "Default: all files."));
        p.put("pattern", Map.of("type", "string",
                "description",
                        "Optional regex. When set, 'lines' counts only matching lines "
                                + "and 'chars' aggregates the matched line text."));
        p.put("caseInsensitive", Map.of("type", "boolean",
                "description", "Match the regex case-insensitively. Default: false."));
        p.put("maxDepth", Map.of("type", "integer",
                "description",
                        "Recursion depth cap when 'path' is a directory. Default: "
                                + DEFAULT_MAX_DEPTH + "."));
        return p;
    }

    @Override public String name() { return "client_file_count"; }
    @Override public String description() {
        return "Count lines, characters, and bytes for a file or — when 'path' is "
                + "a directory — across every file matching a glob. Optional regex "
                + "narrows the line-count to matches (wc-style line/char/byte stats).";
    }
    @Override public boolean primary() { return true; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        String pathRaw = stringOrThrow(params, "path");
        String pathGlob = stringOrNull(params, "pathGlob");
        String patternStr = stringOrNull(params, "pattern");
        boolean ci = Boolean.TRUE.equals(params == null ? null : params.get("caseInsensitive"));
        int maxDepth = clampDepth(intOrNull(params, "maxDepth"));

        Pattern pattern;
        try {
            pattern = patternStr == null
                    ? null
                    : Pattern.compile(patternStr, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex: " + e.getMessage());
        }

        Path target = ClientFilePaths.resolve(pathRaw);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Not found: " + target.toAbsolutePath());
        }
        boolean isDir = Files.isDirectory(target);
        PathMatcher matcher = (isDir && pathGlob != null)
                ? FileSystems.getDefault().getPathMatcher("glob:" + pathGlob)
                : null;

        Counter counter = new Counter();
        if (!isDir) {
            counter.consume(target, target.getFileName(), pattern);
        } else {
            try (Stream<Path> stream = Files.walk(target, maxDepth)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(file)) continue;
                    Path rel = target.relativize(file);
                    if (matcher != null && !matcher.matches(rel)) continue;
                    counter.consume(file, rel, pattern);
                }
            } catch (IOException e) {
                throw new RuntimeException("Walk failed: " + e.getMessage(), e);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", target.toAbsolutePath().toString());
        if (isDir && pathGlob != null) out.put("pathGlob", pathGlob);
        if (patternStr != null) out.put("pattern", patternStr);
        out.put("filesCounted", counter.filesCounted);
        out.put("filesSkipped", counter.filesSkipped);
        out.put("lines", pattern == null ? counter.lines : counter.matchingLines);
        if (pattern != null) out.put("totalLinesScanned", counter.lines);
        out.put("chars", counter.chars);
        out.put("bytes", counter.bytes);
        return out;
    }

    private static final class Counter {
        long lines;
        long matchingLines;
        long chars;
        long bytes;
        int filesCounted;
        int filesSkipped;

        void consume(Path file, Path relativeOrSelf, Pattern pattern) {
            long size;
            try { size = Files.size(file); } catch (IOException ignored) {
                filesSkipped++; return;
            }
            if (size > MAX_FILE_BYTES && pattern == null) {
                try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
                    long count = stream.count();
                    lines += count;
                    chars += size;
                    bytes += size;
                    filesCounted++;
                    return;
                } catch (IOException ignored) {
                    filesSkipped++;
                    return;
                }
            }
            if (size > MAX_FILE_BYTES) { filesSkipped++; return; }
            String content;
            try { content = Files.readString(file, StandardCharsets.UTF_8); }
            catch (IOException ignored) { filesSkipped++; return; }
            // String.lines() matches Files.readAllLines / wc -l semantics:
            // a trailing newline is a line terminator, not an empty line.
            List<String> lineList = content.lines().toList();
            lines += lineList.size();
            bytes += size;
            if (pattern == null) {
                chars += content.length();
            } else {
                long matchedLines = 0;
                long matchedChars = 0;
                for (String l : lineList) {
                    if (pattern.matcher(l).find()) {
                        matchedLines++;
                        matchedChars += l.length();
                    }
                }
                this.matchingLines += matchedLines;
                this.chars += matchedChars;
            }
            filesCounted++;
        }
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
