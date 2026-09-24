package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Kicks a run: sets the script reference (when given) plus optional
 * overrides, then submits the phase machine to the {@code HactarRunService}.
 *
 * <p>F2 (decided): a new start requires the previous run to be STOPPED —
 * while a run is live the tool refuses; call {@code hactar_stop} first.
 * After a terminal run a restart is free, including switching the
 * {@code scriptRef} (an operator chat over several scripts is the normal
 * case).
 *
 * <p>Named {@code hactar_start} (not {@code hactar_run}) deliberately: the
 * existing {@code hactar_run} tool in the general pool SPAWNS a new Hactar
 * process — a session agent sees both, so the names must not collide.
 *
 * <p>Overrides are persisted on the process' engine params (the phases read
 * them from there) — the {@code scriptRef} override goes to the STATE only,
 * never to the engine params: the spawn form (chat vs worker,
 * {@link HactarState#isChatIdentity()}) derives from the engine param's
 * presence at spawn time and must not flip retroactively.
 */
@Component
@RequiredArgsConstructor
public class HactarStartTool extends HactarBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final HactarStateStore stateStore;
    private final HactarRunService runService;

    @Override
    public String name() {
        return "hactar_start";
    }

    @Override
    public String description() {
        return "Start a script run: LOADING → (optional deep-validate) → EXECUTING, executed in "
                + "the background by the phase machine. The previous run must be stopped or "
                + "terminal — stop a live run with hactar_stop first. Params: scriptRef "
                + "(document path; optional when the state already carries one), "
                + "validateBeforeRun (deep LLM review before execution), timeout (seconds or "
                + "'30s'/'5m'/'1h'), scriptParams (bindings as vance.params.*), "
                + "scriptAllowedTools (tools the script may call).";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scriptRef", Map.of("type", "string", "description", "Project document path of the script to run"));
        props.put(
                "validateBeforeRun",
                Map.of(
                        "type",
                        "boolean",
                        "description",
                        "Run the LLM deep-validate gate before executing (default: recipe/param)"));
        props.put(
                "timeout",
                Map.of("type", "string", "description", "Wall-clock cap: seconds or '30s'/'5m'/'1h' (default 5m)"));
        props.put(
                "scriptParams", Map.of("type", "object", "description", "Bindings the script sees as vance.params.*"));
        props.put(
                "scriptAllowedTools",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Tool names the script may call"));
        return Map.of("type", "object", "properties", props);
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) throws ToolException {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        if (runService.isRunning(process.getId())) {
            throw new ToolException("A run is already active — stop it with hactar_stop "
                    + "before starting a new one (F2: a new start requires the previous run stopped).");
        }
        HactarState state = stateStore.load(process);
        if (state.getScriptRef() == null || state.getScriptRef().isBlank()) {
            String ref = stringParam(params, "scriptRef");
            if (ref == null) {
                throw new ToolException("No scriptRef given and the state carries none — "
                        + "pass scriptRef (a project document path).");
            }
            state.setScriptRef(ref);
        } else {
            String ref = stringParam(params, "scriptRef");
            if (ref != null) {
                // After a terminal run: free restart including a script switch.
                state.setScriptRef(ref);
            }
        }
        applyEngineParamOverrides(process, params, state);
        runService.start(process, state);
        return Map.of(
                "started",
                true,
                "scriptRef",
                state.getScriptRef(),
                "validateBeforeRun",
                state.isValidateBeforeRun(),
                "note",
                "run kicked — the phase machine works in the background; "
                        + "you will be woken at the terminal transition; mid-run "
                        + "progress shows in your status block");
    }

    /**
     * Persists the optional overrides on the process' engine params (where
     * the phases read them) and mirrors {@code validateBeforeRun} onto the
     * state (where LOADING branches on it). {@code scriptRef} is
     * deliberately NOT written to the engine params — see the class javadoc.
     */
    private void applyEngineParamOverrides(
            ThinkProcessDocument process, Map<String, Object> params, HactarState state) {
        Map<String, Object> engineParams = process.getEngineParams();
        Map<String, Object> p = engineParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(engineParams);
        boolean dirty = false;

        String timeout = stringParam(params, "timeout");
        if (timeout != null) {
            p.put("timeout", timeout);
            dirty = true;
        }
        Map<String, Object> scriptParams = mapParam(params, "scriptParams");
        if (scriptParams != null) {
            p.put("scriptParams", scriptParams);
            dirty = true;
        }
        java.util.List<String> allowedTools = stringListParam(params, "scriptAllowedTools");
        if (allowedTools != null) {
            p.put("scriptAllowedTools", allowedTools);
            dirty = true;
        }
        Boolean validate = boolParam(params, "validateBeforeRun");
        if (validate != null) {
            p.put("validateBeforeRun", validate);
            state.setValidateBeforeRun(validate);
            dirty = true;
        }
        if (dirty) {
            process.setEngineParams(p);
            thinkProcessService.replaceEngineParams(process.getId(), p);
        }
    }
}
