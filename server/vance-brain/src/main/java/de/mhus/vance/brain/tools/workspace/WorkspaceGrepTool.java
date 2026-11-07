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
 * Searches scratch RootDir files for lines matching a regex —
 * project-side equivalent of {@code grep -rn}. Files larger than
 * 2 MiB or non-UTF-8 are skipped silently so the
 * tool is safe across mixed binary/text trees.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceGrepTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("pattern"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(
                "pattern",
                Map.of("type", "string", "description", "Java regular expression. Plain substrings are fine."));
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
                        "Subdirectory inside the RootDir to search (recursive). " + "Default: the whole RootDir."));
        p.put(
                "pathGlob",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional glob filter on file paths relative to 'path', "
                                + "e.g. '**/*.java'. Default: all files."));
        p.put("caseInsensitive", Map.of("type", "boolean", "description", "Match case-insensitively. Default: false."));
        p.put(
                "contextBefore",
                Map.of("type", "integer", "description", "Number of lines before each match. Default: 0."));
        p.put(
                "contextAfter",
                Map.of("type", "integer", "description", "Number of lines after each match. Default: 0."));
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
                        "Cap on total match rows returned. Default: " + FileWalkDefaults.DEFAULT_LIMIT + ", max: "
                                + RootDirSearch.GREP_MAX_LIMIT + "."));
        p.put(
                "includeGenerated",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "Also search dependency and build directories "
                                + "(node_modules, target, dist, .git, …), which are "
                                + "skipped by default. Set true to search inside a "
                                + "dependency on purpose."));
        return p;
    }

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "work_file_grep";
    }

    @Override
    public Set<String> requiresCapabilities() {
        return Set.of(ToolCapabilities.WORKSPACE);
    }

    @Override
    public String description() {
        return "Recursively grep regex patterns across files in a workspace RootDir. "
                + "Returns matching lines with file path + 1-based line number, "
                + "optionally with context lines. Binary / oversized files are skipped.";
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
        return "Explicit WORK variant of file_grep — targets the brain workspace regardless of the work target. Prefer file_grep.";
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
        return WorkspaceSearch.run(workspace, ctx, params, RootDirSearch::grep);
    }
}
