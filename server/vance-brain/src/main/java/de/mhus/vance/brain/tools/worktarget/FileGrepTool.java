package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.api.tools.FileWalkDefaults;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Generic file-grep. Dispatches to {@code client_file_grep} or
 * {@code work_file_grep} based on the process's current
 * {@link de.mhus.vance.shared.worktarget.WorkTarget}.
 *
 * <p>The schema is the <b>union</b> of both backends, and every param means
 * the same thing on both. {@code path} used to be documented as "CLIENT only"
 * while the WORK backend ignored it outright — a {@code path}-scoped grep
 * silently searched the whole RootDir.
 */
@Component("workTargetFileGrepTool")
public class FileGrepTool extends AbstractWorkTargetTool {

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
                        "Directory to search (recursive) or a single file. CLIENT: "
                                + "absolute or working-dir relative; WORK: relative to "
                                + "the RootDir. Default: the root."));
        p.put("pathGlob", Map.of("type", "string",
                "description",
                        "Glob filter on file paths relative to 'path', "
                                + "e.g. '**/*.java'. Default: all files."));
        p.put("caseInsensitive", Map.of("type", "boolean",
                "description", "Match case-insensitively. Default: false."));
        p.put("contextBefore", Map.of("type", "integer",
                "description", "Number of lines before each match. Default: 0."));
        p.put("contextAfter", Map.of("type", "integer",
                "description", "Number of lines after each match. Default: 0."));
        p.put("maxDepth", Map.of("type", "integer",
                "description",
                        "Recursion depth cap below 'path'. Default: "
                                + FileWalkDefaults.DEFAULT_MAX_DEPTH
                                + ". Use 1 to scan a flat directory."));
        p.put("limit", Map.of("type", "integer",
                "description", "Cap on total match rows. Default: "
                        + FileWalkDefaults.DEFAULT_LIMIT + "."));
        p.put("includeGenerated", Map.of("type", "boolean",
                "description",
                        "Also search dependency and build directories "
                                + "(node_modules, target, dist, .git, …), which are "
                                + "skipped by default."));
        p.put("dirName", Map.of("type", "string",
                "description",
                        "WORK only: override the active RootDir for this call. "
                                + "Ignored when the active target is CLIENT."));
        return p;
    }

    public FileGrepTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_grep"; }
    @Override public String description() {
        return "Recursively grep regex patterns across files at the "
                + "active work target. Returns matching lines with path + 1-based "
                + "line number, optionally with context lines. Dispatches to "
                + "client_file_grep (CLIENT) or work_file_grep (WORK).";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "No matches = pattern/path; timeout = scope too broad, narrow with "
                + "pathGlob/path/maxDepth.";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem", "search");
    }

    @Override protected String clientBackend() { return "client_file_grep"; }
    @Override protected String workBackend()   { return "work_file_grep"; }
}
