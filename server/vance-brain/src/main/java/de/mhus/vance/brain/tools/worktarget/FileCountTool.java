package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.api.tools.FileWalkDefaults;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Generic file-count. Dispatches to {@code client_file_count} or
 * {@code work_file_count} based on the process's current
 * {@link de.mhus.vance.shared.worktarget.WorkTarget}.
 *
 * <p>The schema is the <b>union</b> of both backends, and every param means
 * the same thing on both. It used to expose only {@code path} — described as
 * "File path or glob", which is neither — and hid the glob, regex and
 * case-folding options both backends implement.
 */
@Component("workTargetFileCountTool")
public class FileCountTool extends AbstractWorkTargetTool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of("type", "string",
                "description",
                        "File or directory. Directories are walked recursively. "
                                + "CLIENT: absolute or working-dir relative; WORK: "
                                + "relative to the RootDir. Default: the root."));
        p.put("pathGlob", Map.of("type", "string",
                "description",
                        "Glob filter on file paths relative to 'path'. Default: all "
                                + "files. Ignored when 'path' names a single file."));
        p.put("pattern", Map.of("type", "string",
                "description",
                        "Optional regex. When set, 'lines' counts only matching lines "
                                + "and 'chars' aggregates the matched line text."));
        p.put("caseInsensitive", Map.of("type", "boolean",
                "description", "Match the regex case-insensitively. Default: false."));
        p.put("maxDepth", Map.of("type", "integer",
                "description",
                        "Recursion depth cap when 'path' is a directory. Default: "
                                + FileWalkDefaults.DEFAULT_MAX_DEPTH + "."));
        p.put("includeGenerated", Map.of("type", "boolean",
                "description",
                        "Also count dependency and build directories "
                                + "(node_modules, target, dist, .git, …), which are "
                                + "skipped by default."));
        p.put("dirName", Map.of("type", "string",
                "description",
                        "WORK only: override the active RootDir for this call. "
                                + "Ignored when the active target is CLIENT."));
        return p;
    }

    public FileCountTool(WorkTargetDispatcher dispatcher) { super(dispatcher); }

    @Override public String name() { return "file_count"; }
    @Override public String description() {
        return "Count lines, characters, and bytes for one file or across many "
                + "files at the active work target. An optional regex narrows the "
                + "line count to matches (wc-style stats). Dispatches to "
                + "client_file_count (CLIENT) or work_file_count (WORK).";
    }
    @Override public boolean contributesPrak() {
        // Numeric count — no durable insight.
        return false;
    }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public java.util.Set<String> labels() { return java.util.Set.of("read-only"); }

    @Override
    public @org.jspecify.annotations.Nullable String troubleshootingHint() {
        return "Zero counts = check path/pathGlob; a directory counts its whole "
                + "subtree, narrow with maxDepth.";
    }

    @Override protected String clientBackend() { return "client_file_count"; }
    @Override protected String workBackend()   { return "work_file_count"; }
}
