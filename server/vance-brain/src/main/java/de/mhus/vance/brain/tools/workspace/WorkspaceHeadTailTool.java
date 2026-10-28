package de.mhus.vance.brain.tools.workspace;

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
 * Returns the first {@code head} and / or last {@code tail} lines of
 * a scratch file. Either or both may be specified; at least one is
 * required so the tool always returns a bounded slice rather than the
 * full body.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceHeadTailTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", buildProps(),
            "required", List.of("path"));

    private static Map<String, Object> buildProps() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", Map.of("type", "string", "description", "Relative path inside the RootDir."));
        p.put(
                "dirName",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional RootDir name. Defaults to the current process's temp RootDir."));
        p.put(
                "head",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Lines from the top. 0 / omitted = none. Capped at " + RootDirSearch.HEAD_TAIL_MAX_LINES
                                + "."));
        p.put(
                "tail",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "Lines from the bottom. Capped at " + RootDirSearch.HEAD_TAIL_MAX_LINES + "."));
        return p;
    }

    private final WorkspaceService workspace;

    @Override
    public String name() {
        return "work_file_head_tail";
    }

    @Override
    public Set<String> requiresCapabilities() {
        return Set.of(ToolCapabilities.WORKSPACE);
    }

    @Override
    public String description() {
        return "Return the first N lines (head) and / or last N lines (tail) "
                + "of a workspace file. At least one of head / tail must be > 0. "
                + "Lines are 1-based; the response carries lineNumber so the LLM "
                + "can address them again.";
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
        return "Explicit WORK variant of file_head_tail — targets the brain workspace regardless of the work target. Prefer file_head_tail.";
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, "read-only");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        return WorkspaceSearch.run(workspace, ctx, params, RootDirSearch::headTail);
    }
}
