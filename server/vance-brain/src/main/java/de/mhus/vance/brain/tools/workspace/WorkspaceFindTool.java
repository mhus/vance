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
 * Finds files in a scratch RootDir by glob pattern + size / mtime
 * filters. Pure metadata read — never opens the file body. Default
 * sort: path ascending; the {@code sortBy} parameter switches to
 * {@code mtime} descending so the LLM can ask for "what changed
 * recently" without a follow-up sort.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceFindTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of());

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(
                "dirName",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional RootDir name. Defaults to the current process's temp RootDir."));
        p.put(
                "path",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Subdirectory inside the RootDir to walk. Default: the whole RootDir."));
        p.put(
                "pathGlob",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Glob pattern matched against the relative path under 'path', "
                                + "e.g. '**/*.md' or 'src/**/*.java'. Default: all files."));
        p.put(
                "minSizeBytes",
                Map.of("type", "integer", "description", "Skip files smaller than this. Default: no lower bound."));
        p.put(
                "maxSizeBytes",
                Map.of("type", "integer", "description", "Skip files larger than this. Default: no upper bound."));
        p.put(
                "modifiedAfter",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Only files modified after this ISO-8601 instant "
                                + "(e.g. '2026-01-01T00:00:00Z'). Default: no lower bound."));
        p.put(
                "modifiedBefore",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Only files modified before this ISO-8601 instant. Default: no upper bound."));
        p.put(
                "sortBy",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("path", "mtime", "size"),
                        "description",
                        "Sort key. 'path' (default), 'mtime' (descending), 'size' (descending)."));
        p.put(
                "maxDepth",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Recursion depth cap below 'path'. Default: "
                                + FileWalkDefaults.DEFAULT_MAX_DEPTH
                                + ". Use 1 to scan a flat directory."));
        p.put(
                "limit",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Cap on entries returned. Default: " + FileWalkDefaults.DEFAULT_LIMIT + ", max: "
                                + RootDirSearch.FIND_MAX_LIMIT + "."));
        p.put(
                "includeGenerated",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "Also walk dependency and build directories "
                                + "(node_modules, target, dist, .git, …), which are "
                                + "skipped by default."));
        return p;
    }

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "work_file_find";
    }

    @Override
    public Set<String> requiresCapabilities() {
        return Set.of(ToolCapabilities.WORKSPACE);
    }

    @Override
    public String description() {
        return "Find files inside a workspace RootDir by path glob, size range, and "
                + "modification-time range. Returns relative paths with size + mtime. "
                + "Sort by path (default), mtime, or size.";
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
        return "Explicit WORK variant of file_find — targets the brain workspace regardless of the work target. Prefer file_find.";
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
        return WorkspaceSearch.run(workspace, ctx, params, RootDirSearch::find);
    }
}
