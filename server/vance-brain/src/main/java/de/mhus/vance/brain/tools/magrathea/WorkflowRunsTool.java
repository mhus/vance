package de.mhus.vance.brain.tools.magrathea;

import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.runs.RunSummaryDto;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Agent tool: the workflow runs of this project, newest first — the run screen's list, narrowed
 * to workflow runs. Filtering by workflow name or status happens after the fetch, over a window
 * wider than the limit, so a filter does not come back empty just because the matches are older
 * than the newest few.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class WorkflowRunsTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    /** How many runs are fetched before a filter is applied. */
    private static final int WINDOW = 200;

    private static final Map<String, Object> SCHEMA;

    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(
                "workflow",
                Map.of("type", "string", "description", "Only runs of this workflow (the name workflow_list shows)."));
        props.put(
                "status",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("running", "paused", "done", "failed", "stopped"),
                        "description",
                        "Only runs in this status. A run halted at a breakpoint is 'paused'."));
        props.put(
                "limit",
                Map.of(
                        "type",
                        "integer",
                        "description",
                        "At most this many, default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + "."));
        SCHEMA = Map.of("type", "object", "properties", props, "required", List.of());
    }

    private final WorkflowToolSupport support;

    @Override
    public String name() {
        return "workflow_runs";
    }

    @Override
    public String description() {
        return "List workflow runs of this project, newest first: runId, workflow, status, current "
                + "state, who started it, when. Filter by workflow and status. Open one with "
                + "workflow_run_get. See manual_read('workflow-debug').";
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
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.WORKER, ToolLabels.READ_ONLY, "workflow");
    }

    @Override
    public String searchHint() {
        return "List workflow runs by workflow or status";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        WorkflowToolSupport.Scope scope = support.scope(name(), ctx, Action.READ);
        String workflow = WorkflowToolSupport.text(params, "workflow");
        RunStatus status = parseStatus(WorkflowToolSupport.text(params, "status"));
        int limit = DEFAULT_LIMIT;
        if (params.get("limit") instanceof Number n) {
            limit = Math.min(Math.max(1, n.intValue()), MAX_LIMIT);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (RunSummaryDto run : support.list(scope, WINDOW)) {
            if (workflow != null && !workflow.equalsIgnoreCase(run.getName())) continue;
            if (status != null && run.getStatus() != status) continue;
            if (rows.size() == limit) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runId", WorkflowToolSupport.nativeOf(run.getRunId()));
            row.put("workflow", run.getName());
            row.put("status", run.getStatus() == null ? null : run.getStatus().name());
            row.put("state", run.getStep());
            if (run.getStartedBy() != null) row.put("startedBy", run.getStartedBy());
            row.put("startedAt", Objects.toString(run.getStartedAt(), null));
            row.put("updatedAt", Objects.toString(run.getUpdatedAt(), null));
            if (run.getParentRunId() != null) {
                row.put("parentRunId", WorkflowToolSupport.nativeOf(run.getParentRunId()));
            }
            rows.add(row);
        }
        return Map.of("runs", rows, "count", rows.size());
    }

    private static @Nullable RunStatus parseStatus(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return RunStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ToolException(
                    "unknown status '" + raw + "' — one of running, paused, done, failed, stopped.", ex);
        }
    }
}
