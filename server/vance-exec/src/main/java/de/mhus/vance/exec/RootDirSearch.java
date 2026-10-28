package de.mhus.vance.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * The search half of the {@code file_*} family — {@code find}, {@code grep},
 * {@code count}, {@code head_tail} — over one RootDir folder. The brain's
 * {@code work_file_*} tools and the runner's {@code file.*} operations both call
 * it, so a search means the same thing wherever the RootDir lives.
 *
 * <p>Each method takes the parameter map the tool received (same names as the tool
 * schemas) and returns the map the tool answers with. Failures a caller can fix
 * (bad regex, missing path, nothing to return) are {@link RootDirException}s with a
 * message meant for the model.
 */
public final class RootDirSearch {

    /** Cap on {@code find} rows; the tool schemas quote it. */
    public static final int FIND_MAX_LIMIT = 2_000;
    /** Cap on {@code grep} match rows. */
    public static final int GREP_MAX_LIMIT = 1_000;

    private static final long GREP_MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final long COUNT_MAX_FILE_BYTES = 8L * 1024 * 1024;
    /** Cap on lines per side of {@code head_tail}. */
    public static final int HEAD_TAIL_MAX_LINES = 5_000;

    private static final long REGEX_BUDGET_NANOS = 2_000_000_000L; // per call

    private RootDirSearch() {}

    /** One directory level of a RootDir: entry names, directories with a trailing {@code /}. */
    public static Map<String, Object> list(Path base, String dirName, @Nullable Map<String, Object> params) {
        String subPath = new SearchParams(params).string("path");
        SubPaths.requirePresent(base, subPath, dirName, true);
        String prefix = SubPaths.prefix(subPath);
        // RootDirFiles.list is recursive and file-only; fold it back to one level. The first
        // segment below the prefix is the entry name, a further segment makes it a directory.
        Set<String> entries = new TreeSet<>();
        for (String relPath : RootDirFiles.list(base)) {
            String under = SubPaths.under(relPath, prefix);
            if (under == null) {
                continue;
            }
            int slash = under.indexOf('/');
            entries.add(slash < 0 ? under : under.substring(0, slash) + "/");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", dirName);
        out.put("path", subPath == null ? "." : subPath);
        out.put("entries", List.copyOf(entries));
        out.put("count", entries.size());
        return out;
    }

    /** Files by path glob, size and modification-time range; metadata only, never the body. */
    public static Map<String, Object> find(Path base, String dirName, @Nullable Map<String, Object> params) {
        SearchParams p = new SearchParams(params);
        String subPath = p.string("path");
        Long minSize = p.number("minSizeBytes");
        Long maxSize = p.number("maxSizeBytes");
        Instant after = p.instant("modifiedAfter");
        Instant before = p.instant("modifiedBefore");
        String sortBy = p.string("sortBy");
        int limit = FileWalkDefaults.clampLimit(p.integer("limit"), FIND_MAX_LIMIT);
        int maxDepth = FileWalkDefaults.clampDepth(p.integer("maxDepth"));
        boolean includeGenerated = p.flag("includeGenerated");
        PathMatcher matcher = GlobMatchers.buildGlobMatcher(p.string("pathGlob"));

        // 'path' narrows the walk to a subtree; glob, depth and the generated-filter all
        // judge the part below it.
        SubPaths.requirePresent(base, subPath, dirName, true);
        String prefix = SubPaths.prefix(subPath);
        List<FoundFile> entries = new ArrayList<>();
        int totalConsidered = 0;
        int generatedSkipped = 0;
        for (String relPath : RootDirFiles.list(base)) {
            String under = SubPaths.under(relPath, prefix);
            if (under == null) {
                continue;
            }
            Path underPath = Path.of(under);
            if (!includeGenerated && FileWalkDefaults.isGenerated(underPath)) {
                generatedSkipped++;
                continue;
            }
            if (FileWalkDefaults.depthOf(underPath) > maxDepth) {
                continue;
            }
            totalConsidered++;
            if (matcher != null && !matcher.matches(underPath)) {
                continue;
            }
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(RootPaths.resolveWithin(base, relPath), BasicFileAttributes.class);
            } catch (IOException | RootDirException e) {
                continue;
            }
            long size = attrs.size();
            Instant mtime = attrs.lastModifiedTime().toInstant();
            if (minSize != null && size < minSize) {
                continue;
            }
            if (maxSize != null && size > maxSize) {
                continue;
            }
            if (after != null && !mtime.isAfter(after)) {
                continue;
            }
            if (before != null && !mtime.isBefore(before)) {
                continue;
            }
            entries.add(new FoundFile(relPath, size, mtime));
        }

        switch (sortBy == null ? "path" : sortBy) {
            case "mtime" -> entries.sort((a, b) -> b.mtime.compareTo(a.mtime));
            case "size" -> entries.sort((a, b) -> Long.compare(b.size, a.size));
            default -> entries.sort((a, b) -> a.path.compareTo(b.path));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (FoundFile e : entries.subList(0, Math.min(limit, entries.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", e.path);
            row.put("size", e.size);
            row.put("modifiedAt", e.mtime.toString());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", dirName);
        out.put("path", subPath == null ? "." : subPath);
        out.put("totalConsidered", totalConsidered);
        putGeneratedNote(out, generatedSkipped, "walk");
        out.put("matchCount", entries.size());
        out.put("returned", rows.size());
        out.put("truncated", entries.size() > limit);
        out.put("entries", rows);
        return out;
    }

    private record FoundFile(String path, long size, Instant mtime) {}

    /** Lines matching a regex across the RootDir's files, like {@code grep -rn}. */
    public static Map<String, Object> grep(Path base, String dirName, @Nullable Map<String, Object> params) {
        SearchParams p = new SearchParams(params);
        String patternStr = p.required("pattern");
        String subPath = p.string("path");
        int before = nonNegative(p.integer("contextBefore"));
        int after = nonNegative(p.integer("contextAfter"));
        int limit = FileWalkDefaults.clampLimit(p.integer("limit"), GREP_MAX_LIMIT);
        int maxDepth = FileWalkDefaults.clampDepth(p.integer("maxDepth"));
        boolean includeGenerated = p.flag("includeGenerated");
        Pattern pattern = compile(patternStr, p.flag("caseInsensitive"));
        PathMatcher matcher = GlobMatchers.buildGlobMatcher(p.string("pathGlob"));
        // One wall-clock budget for the whole grep: an (untrusted) regex runs against every
        // line of every scanned file, and a catastrophic-backtracking pattern must not pin
        // the thread.
        long deadline = System.nanoTime() + REGEX_BUDGET_NANOS;

        SubPaths.requirePresent(base, subPath, dirName, true);
        String prefix = SubPaths.prefix(subPath);
        List<Map<String, Object>> matches = new ArrayList<>();
        int filesScanned = 0;
        int filesSkipped = 0;
        int generatedSkipped = 0;
        boolean truncated = false;
        for (String relPath : RootDirFiles.list(base)) {
            if (matches.size() >= limit) {
                truncated = true;
                break;
            }
            String under = SubPaths.under(relPath, prefix);
            if (under == null) {
                continue;
            }
            Path underPath = Path.of(under);
            if (!includeGenerated && FileWalkDefaults.isGenerated(underPath)) {
                generatedSkipped++;
                continue;
            }
            if (FileWalkDefaults.depthOf(underPath) > maxDepth) {
                continue;
            }
            if (matcher != null && !matcher.matches(underPath)) {
                continue;
            }
            String[] lines;
            try {
                Path absolute = RootPaths.resolveWithin(base, relPath);
                if (Files.size(absolute) > GREP_MAX_FILE_BYTES) {
                    filesSkipped++;
                    continue;
                }
                lines = Files.readString(absolute, StandardCharsets.UTF_8).split("\\R", -1);
            } catch (IOException | RootDirException e) {
                // Binary, non-UTF-8 or vanished: skipped silently so a mixed tree is safe to search.
                filesSkipped++;
                continue;
            }
            filesScanned++;
            for (int i = 0; i < lines.length; i++) {
                if (!matches(pattern, lines[i], deadline)) {
                    continue;
                }
                if (matches.size() >= limit) {
                    truncated = true;
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", relPath);
                m.put("lineNumber", i + 1);
                m.put("line", lines[i]);
                if (before > 0 || after > 0) {
                    List<Map<String, Object>> context = new ArrayList<>();
                    int from = Math.max(0, i - before);
                    int to = Math.min(lines.length - 1, i + after);
                    for (int j = from; j <= to; j++) {
                        if (j != i) {
                            context.add(Map.of("lineNumber", j + 1, "line", lines[j]));
                        }
                    }
                    if (!context.isEmpty()) {
                        m.put("context", context);
                    }
                }
                matches.add(m);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", dirName);
        out.put("path", subPath == null ? "." : subPath);
        out.put("pattern", patternStr);
        out.put("filesScanned", filesScanned);
        out.put("filesSkipped", filesSkipped);
        putGeneratedNote(out, generatedSkipped, "search");
        out.put("matchCount", matches.size());
        out.put("truncated", truncated);
        out.put("matches", matches);
        return out;
    }

    /**
     * Lines, characters and bytes of one file or of every file of a subtree; with a
     * {@code pattern} the line count becomes "lines that match".
     */
    public static Map<String, Object> count(Path base, String dirName, @Nullable Map<String, Object> params) {
        SearchParams p = new SearchParams(params);
        String pathArg = p.string("path");
        String pathGlob = p.string("pathGlob");
        String patternStr = p.string("pattern");
        int maxDepth = FileWalkDefaults.clampDepth(p.integer("maxDepth"));
        boolean includeGenerated = p.flag("includeGenerated");
        Pattern pattern = patternStr == null ? null : compile(patternStr, p.flag("caseInsensitive"));
        long deadline = System.nanoTime() + REGEX_BUDGET_NANOS;

        // 'path' is a file *or* a directory: a file counts just that file, a directory
        // walks its subtree, none walks the whole RootDir.
        boolean singleFile = pathArg != null && Files.isRegularFile(RootPaths.resolveWithin(base, pathArg));
        if (!singleFile) {
            // Not a file, so it has to be an existing directory — a mistyped path must not
            // answer "0 files", which reads as "the directory is empty".
            SubPaths.requirePresent(base, pathArg, dirName, true);
        }
        String prefix = singleFile ? "" : SubPaths.prefix(pathArg);
        List<String> files = singleFile ? List.of(pathArg) : RootDirFiles.list(base);
        PathMatcher matcher = singleFile ? null : GlobMatchers.buildGlobMatcher(pathGlob);

        long totalLines = 0;
        long totalMatchingLines = 0;
        long totalChars = 0;
        long totalBytes = 0;
        int filesCounted = 0;
        int filesSkipped = 0;
        int generatedSkipped = 0;
        for (String relPath : files) {
            if (!singleFile) {
                String under = SubPaths.under(relPath, prefix);
                if (under == null) {
                    continue;
                }
                Path underPath = Path.of(under);
                if (!includeGenerated && FileWalkDefaults.isGenerated(underPath)) {
                    generatedSkipped++;
                    continue;
                }
                if (FileWalkDefaults.depthOf(underPath) > maxDepth) {
                    continue;
                }
                if (matcher != null && !matcher.matches(underPath)) {
                    continue;
                }
            }
            Path abs;
            try {
                abs = RootPaths.resolveWithin(base, relPath);
            } catch (RootDirException e) {
                if (singleFile) {
                    throw e;
                }
                filesSkipped++;
                continue;
            }
            if (!Files.isRegularFile(abs)) {
                if (singleFile) {
                    throw new RootDirException("Not a regular file: " + relPath);
                }
                filesSkipped++;
                continue;
            }
            long bytes;
            try {
                bytes = Files.size(abs);
            } catch (IOException e) {
                if (singleFile) {
                    throw new RootDirException("Stat failed: " + e.getMessage(), e);
                }
                filesSkipped++;
                continue;
            }
            if (bytes > COUNT_MAX_FILE_BYTES && pattern == null) {
                // No pattern: count lines as a stream instead of holding the body.
                try (var stream = Files.lines(abs, StandardCharsets.UTF_8)) {
                    totalLines += stream.count();
                    totalChars += bytes;
                    totalBytes += bytes;
                    filesCounted++;
                } catch (IOException | java.io.UncheckedIOException e) {
                    filesSkipped++;
                }
                continue;
            }
            if (bytes > COUNT_MAX_FILE_BYTES) {
                filesSkipped++;
                continue;
            }
            String content;
            try {
                content = Files.readString(abs, StandardCharsets.UTF_8);
            } catch (IOException e) {
                if (singleFile) {
                    throw new RootDirException("Read failed: " + e.getMessage(), e);
                }
                filesSkipped++;
                continue;
            }
            // String.lines() matches wc -l: a trailing newline terminates a line, it does not start one.
            List<String> lines = content.lines().toList();
            totalLines += lines.size();
            totalBytes += bytes;
            if (pattern == null) {
                totalChars += content.length();
            } else {
                for (String line : lines) {
                    if (matches(pattern, line, deadline)) {
                        totalMatchingLines++;
                        totalChars += line.length();
                    }
                }
            }
            filesCounted++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dirName", dirName);
        out.put("path", pathArg == null ? "." : pathArg);
        if (pathGlob != null && !singleFile) {
            out.put("pathGlob", pathGlob);
        }
        if (patternStr != null) {
            out.put("pattern", patternStr);
        }
        out.put("filesCounted", filesCounted);
        out.put("filesSkipped", filesSkipped);
        putGeneratedNote(out, generatedSkipped, "count");
        out.put("lines", pattern == null ? totalLines : totalMatchingLines);
        if (pattern != null) {
            out.put("totalLinesScanned", totalLines);
        }
        out.put("chars", totalChars);
        out.put("bytes", totalBytes);
        return out;
    }

    /** The first and/or last N lines of a file, numbered so the caller can address them again. */
    public static Map<String, Object> headTail(Path base, String dirName, @Nullable Map<String, Object> params) {
        SearchParams p = new SearchParams(params);
        String relPath = p.required("path");
        int head = lineCount(p.integer("head"));
        int tail = lineCount(p.integer("tail"));
        if (head == 0 && tail == 0) {
            throw new RootDirException("At least one of 'head' or 'tail' must be > 0");
        }
        Path absolute = RootPaths.resolveWithin(base, relPath);
        if (!Files.exists(absolute)) {
            throw new RootDirException("Not found: " + relPath);
        }
        if (!Files.isRegularFile(absolute)) {
            throw new RootDirException("Not a regular file: " + relPath);
        }
        List<String> all;
        try {
            all = Files.readAllLines(absolute, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RootDirException("Read failed: " + e.getMessage(), e);
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
            int start = total - n;
            List<Map<String, Object>> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rows.add(Map.of("lineNumber", start + i + 1, "line", all.get(start + i)));
            }
            out.put("tail", rows);
        }
        return out;
    }

    // ───────────────────── helpers ─────────────────────

    private static Pattern compile(String regex, boolean caseInsensitive) {
        try {
            return Pattern.compile(regex, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new RootDirException("Invalid regex: " + e.getMessage(), e);
        }
    }

    /** {@link RegexGuard#find} with a budget overrun turned into an error the model can act on. */
    private static boolean matches(Pattern pattern, String line, long deadline) {
        try {
            return RegexGuard.find(pattern, line, deadline);
        } catch (RegexGuard.RegexBudgetExceeded e) {
            throw new RootDirException(
                    "regex too slow — possible catastrophic backtracking; "
                            + "simplify the pattern (avoid nested quantifiers like (a+)+).",
                    e);
        }
    }

    /** What the default filter removed, so the caller does not read the result as a complete sweep. */
    private static void putGeneratedNote(Map<String, Object> out, int skipped, String verb) {
        if (skipped > 0) {
            out.put("generatedFilesSkipped", skipped);
            out.put(
                    "generatedFilesHint",
                    "Dependency/build directories were skipped — pass includeGenerated=true to " + verb + " them.");
        }
    }

    private static int nonNegative(@Nullable Integer raw) {
        return raw == null ? 0 : Math.max(0, raw);
    }

    private static int lineCount(@Nullable Integer raw) {
        return raw == null ? 0 : Math.min(HEAD_TAIL_MAX_LINES, Math.max(0, raw));
    }
}
