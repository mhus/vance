package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.api.tools.FileWalkDefaults;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Generic file-find. Dispatches to {@code client_file_find} or
 * {@code work_file_find} based on the process's current
 * {@link de.mhus.vance.shared.worktarget.WorkTarget}.
 *
 * <p>The schema is the <b>union</b> of both backends, and every param means
 * the same thing on both. It used to advertise {@code glob} — a name neither
 * backend reads — while hiding the size, mtime, sort and limit filters they
 * both support, which pushed callers into {@code exec_run "find …"}.
 */
@Component("workTargetFileFindTool")
public class FileFindTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of("type", "string",
                "description",
                        "Directory to walk. CLIENT: absolute or working-dir relative; "
                                + "WORK: relative to the RootDir. Default: the root."));
        p.put("pathGlob", Map.of("type", "string",
                "description",
                        "Glob matched against the relative path under 'path', "
                                + "e.g. '**/*.java'. Default: all files."));
        p.put("minSizeBytes", Map.of("type", "integer",
                "description", "Skip files smaller than this. Default: no lower bound."));
        p.put("maxSizeBytes", Map.of("type", "integer",
                "description", "Skip files larger than this. Default: no upper bound."));
        p.put("modifiedAfter", Map.of("type", "string",
                "description",
                        "ISO-8601 instant — only files modified strictly after. "
                                + "Default: no lower bound."));
        p.put("modifiedBefore", Map.of("type", "string",
                "description",
                        "ISO-8601 instant — only files modified strictly before. "
                                + "Default: no upper bound."));
        p.put("sortBy", Map.of("type", "string",
                "enum", List.of("path", "mtime", "size"),
                "description",
                        "Sort key. 'path' (default), 'mtime' (descending), "
                                + "'size' (descending)."));
        p.put("maxDepth", Map.of("type", "integer",
                "description",
                        "Recursion depth cap below 'path'. Default: "
                                + FileWalkDefaults.DEFAULT_MAX_DEPTH
                                + ". Use 1 to scan a flat directory."));
        p.put("limit", Map.of("type", "integer",
                "description", "Cap on entries returned. Default: "
                        + FileWalkDefaults.DEFAULT_LIMIT + "."));
        p.put("includeGenerated", Map.of("type", "boolean",
                "description",
                        "Also walk dependency and build directories "
                                + "(node_modules, target, dist, .git, …), which are "
                                + "skipped by default."));
        p.put("dirName", Map.of("type", "string",
                "description",
                        "WORK only: override the active RootDir for this call. "
                                + "Ignored when the active target is CLIENT."));
        return p;
    }

    public FileFindTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_find"; }
    @Override public String description() {
        return "Find files at the active work target by path glob, size range, "
                + "and modification-time range. Returns relative paths with size "
                + "+ mtime. Dispatches to client_file_find (CLIENT) or "
                + "work_file_find (WORK).";
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "No results = check path/pathGlob; timeout = scope too broad, "
                + "narrow with path or maxDepth.";
    }

    @Override
    public java.util.Set<String> prakLabels() {
        return java.util.Set.of("filesystem", "search");
    }

    @Override protected String clientBackend() { return "client_file_find"; }
    @Override protected String workBackend()   { return "work_file_find"; }
}
