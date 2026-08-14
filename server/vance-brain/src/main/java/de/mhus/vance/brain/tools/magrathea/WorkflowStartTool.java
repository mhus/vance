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
 * <p><b>Two ways in, one of them headless either way.</b> {@code name}
 * goes through the cascade, {@code path} names one document wherever it
 * lies — the same pair the engine offers, because an agent that can only
 * say one of them starts copying files to say the other. What the tool
 * cannot do is make somebody wait for the run: it belongs to the project,
 * and everything the plan needs from a person goes to the inbox. A plan
 * whose questions should reach the conversation is spawned as a worker on
 * the {@code vogon} recipe instead.
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
                        + "project's _vance/workflows/<name>.yaml cascade "
                        + "(project before tenant). Use 'path' instead for a plan "
                        + "stored anywhere else."));
        props.put("path", Map.of(
                "type", "string",
                "description", "Document path of the plan inside this project, "
                        + "e.g. 'workflows/helloworld.yaml'. Starts exactly that "
                        + "document — no cascade, no copying required. Give either "
                        + "'name' or 'path', not both."));
        props.put("params", Map.of(
                "type", "object",
                "description", "Free-form caller params, validated against the "
                        + "workflow's parameters: block. Missing required params "
                        + "fail the start."));
        // Neither is required on its own — invoke() enforces exactly one,
        // which a JSON schema cannot say without anyOf that models read
        // poorly. A clear error beats a clever schema here.
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of());
    }

    private final MagratheaWorkflowService workflowService;

    @Override public String name() { return "workflow_start"; }

    @Override public String description() {
        return "Start a workflow run, unattended. Address the plan either by "
                + "'name' (resolved against _vance/workflows/<name>.yaml, project "
                + "before tenant) or by 'path' (any document in this project) — "
                + "never copy a plan into _vance/workflows/ to make it startable. "
                + "Returns a workflowRunId for later status calls. The run belongs "
                + "to the project: anything the plan needs from a person goes to "
                + "the inbox, and nobody is waiting on the result. When somebody "
                + "IS waiting — they asked for it and want the answer here — spawn "
                + "the 'vogon' recipe instead. See manual_read('plans').";
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
        String name = trimmedOrNull(params, "name");
        String path = trimmedOrNull(params, "path");

        // A name that is plainly a path is taken as one. Refusing it would
        // be pedantry: the value already says how it wants to be resolved,
        // and the alternative an agent reaches for is copying the file.
        if (path == null && name != null && looksLikePath(name)) {
            path = name;
            name = null;
        }
        if (name == null && path == null) {
            throw new ToolException(
                    "Give either 'name' (a plan under _vance/workflows/) or "
                            + "'path' (a plan document anywhere in this project)");
        }
        if (name != null && path != null) {
            throw new ToolException(
                    "Give either 'name' or 'path', not both — they are two ways to "
                            + "address one plan");
        }
        Map<String, Object> callerParams = readParamsMap(params);

        String runId;
        try {
            runId = path != null
                    ? workflowService.startFromDocument(
                            ctx.tenantId(), ctx.projectId(), path, callerParams, ctx.userId())
                    : workflowService.start(
                            ctx.tenantId(), ctx.projectId(), name, callerParams, ctx.userId());
        } catch (MagratheaWorkflowService.MagratheaWorkflowException ex) {
            throw new ToolException(ex.getMessage(), ex);
        } catch (MagratheaWorkflowParseException ex) {
            throw new ToolException("Workflow YAML invalid: " + ex.getMessage(), ex);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowRunId", runId);
        result.put("workflowName", name != null ? name : stemOf(path));
        if (path != null) result.put("workflowPath", path);
        return result;
    }

    private static @org.jspecify.annotations.Nullable String trimmedOrNull(
            Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s)) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Same reading as the engine uses: a slash or a YAML suffix means path. */
    private static boolean looksLikePath(String ref) {
        return ref.contains("/") || ref.endsWith(".yaml") || ref.endsWith(".yml");
    }

    /** File stem — what the run is called in listings when addressed by path. */
    private static String stemOf(String path) {
        String stem = path.substring(path.lastIndexOf('/') + 1);
        int dot = stem.lastIndexOf('.');
        return dot > 0 ? stem.substring(0, dot) : stem;
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
