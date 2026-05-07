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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Counts lines / characters / bytes for one workspace file or — when
 * {@code path} is omitted — across every file in the RootDir matched
 * by an optional {@code pathGlob}. With a {@code pattern} parameter
 * the line-count switches to "lines that match the regex", which lets
 * the LLM check things like "how many TODOs are left" without
 * spawning a worker.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceCountTool implements Tool {

    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of("type", "string",
                "description",
                        "Optional single file (relative path inside the RootDir). "
                                + "When omitted, counts across all files matching pathGlob."));
        p.put("dirName", Map.of("type", "string",
                "description", "Optional RootDir name. Defaults to the current process's temp RootDir."));
        p.put("pathGlob", Map.of("type", "string",
                "description",
                        "Glob filter on file paths. Default: all files. Ignored "
                                + "when 'path' is set."));
        p.put("pattern", Map.of("type", "string",
                "description",
                        "Optional regex. When set, 'lines' counts only matching lines "
                                + "and 'chars'/'bytes' aggregate the matched line text."));
        p.put("caseInsensitive", Map.of("type", "boolean",
                "description", "Match the regex case-insensitively. Default: false."));
        return p;
    }

    private final WorkspaceService workspace;

    @Override public String name() { return "workspace_count"; }
    @Override public String description() {
        return "Count lines, characters, and bytes for a single workspace file or "
                + "across many files matching a glob. Optional regex narrows the "
                + "line-count to matches (wc-style line/char/byte stats).";
    }
    @Override public boolean primary() { return true; }
    @Override public Set<String> labels() { return Set.of("read-only"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String dirName = WorkspaceDirResolver.resolve(workspace, ctx, stringOrNull(params, "dirName"));
        String singlePath = stringOrNull(params, "path");
        String pathGlob = stringOrNull(params, "pathGlob");
        String patternStr = stringOrNull(params, "pattern");
        boolean ci = Boolean.TRUE.equals(params == null ? null : params.get("caseInsensitive"));

        Pattern pattern;
        try {
            pattern = patternStr == null
                    ? null
                    : Pattern.compile(patternStr, ci ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolException("Invalid regex: " + e.getMessage());
        }

        List<String> files;
        if (singlePath != null) {
            files = List.of(singlePath);
        } else {
            try {
                files = workspace.list(ctx.tenantId(), ctx.projectId(), dirName);
            } catch (WorkspaceException e) {
                throw new ToolException(e.getMessage(), e);
            }
        }
        PathMatcher matcher = (singlePath == null && pathGlob != null)
                ? FileSystems.getDefault().getPathMatcher("glob:" + pathGlob)
                : null;

        long totalLines = 0;
        long totalMatchingLines = 0;
        long totalChars = 0;
        long totalBytes = 0;
        int filesCounted = 0;
        int filesSkipped = 0;
        for (String relPath : files) {
            if (matcher != null && !matcher.matches(Path.of(relPath))) continue;
            Path abs;
            try {
                abs = workspace.resolve(ctx.tenantId(), ctx.projectId(), dirName, relPath);
            } catch (WorkspaceException e) {
                if (singlePath != null) throw new ToolException(e.getMessage(), e);
                filesSkipped++;
                continue;
            }
            if (!Files.exists(abs) || !Files.isRegularFile(abs)) {
                if (singlePath != null) {
                    throw new ToolException("Not a regular file: " + relPath);
                }
                filesSkipped++;
                continue;
            }
            long bytes;
            try {
                bytes = Files.size(abs);
            } catch (IOException e) {
                if (singlePath != null) {
                    throw new ToolException("Stat failed: " + e.getMessage(), e);
                }
                filesSkipped++;
                continue;
            }
            if (bytes > MAX_FILE_BYTES && pattern == null) {
                // Fast path: no pattern, fall back to line count via stream.
                try (var stream = Files.lines(abs, StandardCharsets.UTF_8)) {
                    long lineCount = stream.count();
                    totalLines += lineCount;
                    totalChars += bytes;
                    totalBytes += bytes;
                    filesCounted++;
                    continue;
                } catch (IOException e) {
                    filesSkipped++;
                    continue;
                }
            }
            if (bytes > MAX_FILE_BYTES) {
                filesSkipped++;
                continue;
            }
            String content;
            try {
                content = Files.readString(abs, StandardCharsets.UTF_8);
            } catch (IOException e) {
                if (singlePath != null) {
                    throw new ToolException("Read failed: " + e.getMessage(), e);
                }
                filesSkipped++;
                continue;
            }
            // String.lines() matches Files.readAllLines / wc -l semantics:
            // a trailing newline is a line terminator, not an empty line.
            List<String> lines = content.lines().toList();
            totalLines += lines.size();
            totalBytes += bytes;
            if (pattern == null) {
                totalChars += content.length();
            } else {
                long matchedChars = 0;
                long matchedLines = 0;
                for (String line : lines) {
                    if (pattern.matcher(line).find()) {
                        matchedLines++;
                        matchedChars += line.length();
                    }
                }
                totalMatchingLines += matchedLines;
                totalChars += matchedChars;
            }
            filesCounted++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", dirName);
        if (singlePath != null) out.put("path", singlePath);
        if (pathGlob != null && singlePath == null) out.put("pathGlob", pathGlob);
        if (patternStr != null) out.put("pattern", patternStr);
        out.put("filesCounted", filesCounted);
        out.put("filesSkipped", filesSkipped);
        out.put("lines", pattern == null ? totalLines : totalMatchingLines);
        if (pattern != null) out.put("totalLinesScanned", totalLines);
        out.put("chars", totalChars);
        out.put("bytes", totalBytes);
        return out;
    }

    private static String stringOrNull(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
