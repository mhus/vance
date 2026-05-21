package de.mhus.vance.brain.tools.magrathea;

import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowParseException;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Agent tool that starts a Magrathea workflow run from inside an engine
 * (plan §8.1). The LLM provides {@code name} (a workflow defined under
 * {@code _vance/workflows/} in the current project or {@code _vance})
 * and an optional {@code params} object. Returns the freshly
 * generated {@code workflowRunId} so the agent can refer to the run
 * (status check, cancel) afterwards.
 *
 * <p>Default-off — Tenants and recipes opt this tool in. Side-effect
 * label is set so dynamic-tool-bundle policies can gate it.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
@de.mhus.vance.toolpack.SpawnTool
public class WorkflowStartTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Workflow definition name — resolved against the "
                        + "project's _vance/workflows/<name>.yaml cascade."));
        props.put("params", Map.of(
                "type", "object",
                "description", "Free-form caller params, validated against the "
                        + "workflow's parameters: block. Missing required params "
                        + "fail the start."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name"));
    }

    private final MagratheaWorkflowService workflowService;

    @Override public String name() { return "workflow_start"; }

    @Override public String description() {
        return "Start a Magrathea workflow run by name. Returns the workflowRunId "
                + "so subsequent calls (status, cancel) can reference the run. "
                + "Workflows live under _vance/workflows/ in the project's "
                + "document layer; see plan/workflow-service.md.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "workflow", "side-effect"); }

    @Override
    public String searchHint() {
        return "Spawn a Magrathea workflow run";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null || ctx.projectId().isBlank()) {
            throw new ToolException("workflow_start requires a project context");
        }
        Object rawName = params == null ? null : params.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required and must be a non-empty string");
        }
        Map<String, Object> callerParams = readParamsMap(params);

        String runId;
        try {
            runId = workflowService.start(
                    ctx.tenantId(),
                    ctx.projectId(),
                    name,
                    callerParams,
                    ctx.userId());
        } catch (MagratheaWorkflowService.MagratheaWorkflowException ex) {
            throw new ToolException(ex.getMessage(), ex);
        } catch (MagratheaWorkflowParseException ex) {
            throw new ToolException("Workflow YAML invalid: " + ex.getMessage(), ex);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowRunId", runId);
        result.put("workflowName", name);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readParamsMap(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("params");
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new ToolException("'params' must be an object");
    }
}
