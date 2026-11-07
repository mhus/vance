package de.mhus.vance.brain.tools.workspace;

import de.mhus.vance.exec.FileWalkDefaults;
import de.mhus.vance.exec.RootDirSearch;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolCapabilities;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Counts lines / characters / bytes for one scratch file or — when
 * {@code path} is omitted — across every file in the RootDir matched
 * by an optional {@code pathGlob}. With a {@code pattern} parameter
 * the line-count switches to "lines that match the regex", which lets
 * the LLM check things like "how many TODOs are left" without
 * spawning a worker.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceCountTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(
                "path",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "File or subdirectory inside the RootDir. Directories are "
                                + "walked recursively. Default: the whole RootDir."));
        p.put(
                "dirName",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional RootDir name. Defaults to the current process's temp RootDir."));
        p.put(
                "pathGlob",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Glob filter on file paths relative to 'path'. Default: all "
                                + "files. Ignored when 'path' names a single file."));
        p.put(
                "pattern",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional regex. When set, 'lines' counts only matching lines "
                                + "and 'chars'/'bytes' aggregate the matched line text."));
        p.put(
                "caseInsensitive",
                Map.of("type", "boolean", "description", "Match the regex case-insensitively. Default: false."));
        p.put(
                "maxDepth",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Recursion depth cap when 'path' is a directory. Default: " + FileWalkDefaults.DEFAULT_MAX_DEPTH
                                + "."));
        p.put(
                "includeGenerated",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "Also count dependency and build directories "
                                + "(node_modules, target, dist, .git, …), which are "
                                + "skipped by default."));
        return p;
    }

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "work_file_count";
    }

    @Override
    public Set<String> requiresCapabilities() {
        return Set.of(ToolCapabilities.WORKSPACE);
    }

    @Override
    public String description() {
        return "Count lines, characters, and bytes for a single workspace file or "
                + "across many files matching a glob. Optional regex narrows the "
                + "line-count to matches (wc-style line/char/byte stats).";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Explicit WORK variant of file_count — targets the brain workspace regardless of the work target. Prefer file_count.";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, "read-only", "work-file");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        return WorkspaceSearch.run(workspace, ctx, params, RootDirSearch::count);
    }
}
