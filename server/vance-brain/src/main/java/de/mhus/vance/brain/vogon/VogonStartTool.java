package de.mhus.vance.brain.vogon;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Starts a plan run on this process — the session identity's kick verb.
 *
 * <p>Delegates to the engine's start path including {@code VogonIntake}
 * (decision F2, Weg A): explicit params win, the plan choice and the
 * parameter extraction run exactly as they do for a spawn, and intake
 * errors come back as this tool's result so the identity can translate
 * them into the conversation. The headless close-on-failure contract does
 * NOT apply here: a chat process survives a failed start and can try
 * another plan.
 *
 * <p>A new start requires the previous run stopped or terminal
 * (decision F4/Hactar-F2 formula): while a run is live the tool refuses —
 * call {@code vogon_stop} first. After a terminal run a restart is free,
 * including switching to a different plan.
 *
 * <p>Named {@code vogon_start} (not {@code workflow_start}) deliberately:
 * it operates on THIS process' run; the general {@code workflow_start}
 * spawns unattended runs elsewhere.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
public class VogonStartTool extends VogonBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final VogonEngine engine;

    public VogonStartTool(ThinkProcessService thinkProcessService, VogonEngine engine) {
        this.thinkProcessService = thinkProcessService;
        this.engine = engine;
    }

    @Override
    public String name() {
        return "vogon_start";
    }

    @Override
    public String description() {
        return "Start a plan run on this process: the runner drives the plan's states in the "
                + "background. Params: workflow (plan name, resolved through the workflow cascade) "
                + "or workflowPath (a plan document path in this project) — exactly one; params "
                + "(plan parameters as a map, defaults apply as declared); task (what the user "
                + "asked for, verbatim — the intake may read missing plan parameters out of it). "
                + "The previous run must be stopped or terminal — stop a live run with vogon_stop "
                + "first. After a terminal run a restart is free, including a different plan. "
                + "You will be woken when the run waits at a gate and when it ends.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(
                "workflow",
                Map.of("type", "string", "description", "Plan name, resolved through the workflow cascade"));
        props.put("workflowPath", Map.of("type", "string", "description", "Plan document path inside this project"));
        props.put(
                "params",
                Map.of("type", "object", "description", "Plan parameters (caller params; declared defaults apply)"));
        props.put(
                "task",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "What the user asked for, verbatim — the intake may read missing plan "
                                + "parameters out of it"));
        return Map.of("type", "object", "properties", props);
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) throws ToolException {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        if (engine.runIsLive(process)) {
            throw new ToolException("A plan run is already active — stop it with vogon_stop before "
                    + "starting a new one (a new start requires the previous run stopped).");
        }

        applyPlanOverrides(process, params);
        // Re-fetch: applyPlanOverrides persisted new engine params, and the
        // start path must read them, not the pre-write snapshot.
        ThinkProcessDocument fresh = process(thinkProcessService, ctx);
        String taskText = stringParam(params, "task");
        String runId;
        try {
            runId = engine.startPlan(fresh, taskText);
        } catch (RuntimeException e) {
            throw new ToolException("Plan start failed: " + e.getMessage(), e);
        }
        return Map.of(
                "started",
                true,
                "workflowRunId",
                runId,
                "note",
                "run kicked — the runner works in the background; you will be woken when "
                        + "the run waits at a gate and at its terminal transition; your status block "
                        + "carries the live state");
    }

    /**
     * Persists the plan reference (and extra caller params) on the process'
     * engine params — the start path reads them from there, exactly as a
     * spawn would have set them. Exactly one of {@code workflow}/
     * {@code workflowPath} may be given; giving one clears the other (the
     * two are alternative addresses for the same question, and a stale
     * entry of the other form would win the resolution race).
     * {@code workflowRunId} is deliberately NOT cleared: the old terminal
     * run stays readable for forensics until the new run's id replaces it.
     */
    private void applyPlanOverrides(ThinkProcessDocument process, Map<String, Object> params) {
        Map<String, Object> engineParams = process.getEngineParams();
        Map<String, Object> p = engineParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(engineParams);
        boolean dirty = false;

        String workflow = stringParam(params, "workflow");
        String workflowPath = stringParam(params, "workflowPath");
        if (workflow != null && workflowPath != null) {
            throw new ToolException("Give exactly one of workflow (name) or workflowPath (document "
                    + "path) — both at once are two answers to one question.");
        }
        if (workflow != null) {
            p.put(VogonEngine.PARAM_WORKFLOW, workflow);
            p.remove(VogonEngine.PARAM_WORKFLOW_PATH);
            dirty = true;
        } else if (workflowPath != null) {
            p.put(VogonEngine.PARAM_WORKFLOW_PATH, workflowPath);
            p.remove(VogonEngine.PARAM_WORKFLOW);
            dirty = true;
        }

        Map<String, Object> planParams = mapParam(params, "params");
        if (planParams != null && !planParams.isEmpty()) {
            // Vogon's own control keys never become plan parameters.
            planParams.keySet().removeIf(VogonEngine::isControlKey);
            p.putAll(planParams);
            dirty = true;
        }

        if (dirty) {
            process.setEngineParams(p);
            thinkProcessService.replaceEngineParams(process.getId(), p);
        }
    }
}
