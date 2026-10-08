package de.mhus.vance.brain.marvin;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Starts a deep-think tree run on this process — the identity's kick verb.
 *
 * <p>A start requires the previous tree stopped or terminal (Hactar-F2
 * formula): a live tree refuses — call {@code marvin_stop} first. After a
 * terminal run the restart is free: the old (terminal) tree is deleted —
 * its result lives on in the conversation ({@code [tree]} notes and your
 * narration), and the new tree is the process' plan again — exactly one
 * root per process, so every node query stays unambiguous.
 *
 * <p><b>The tree reads only the goal</b> (planning §3.1, Folge-Lauf-Regel):
 * the tree's phases are memoryless and a chat process has no parent whose
 * history {@code inheritContext} could read. When the user builds on what
 * a previous run found, distill those findings INTO the goal — a bare
 * "continue with that" gives the new tree nothing.
 */
@Component
public class MarvinStartTool extends MarvinBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final MarvinEngine engine;

    public MarvinStartTool(ThinkProcessService thinkProcessService, MarvinEngine engine) {
        this.thinkProcessService = thinkProcessService;
        this.engine = engine;
    }

    @Override
    public String name() {
        return "marvin_start";
    }

    @Override
    public String description() {
        return "Start a deep-think tree run on this process: the node machine walks the "
                + "task tree in the background (one node phase per turn, questions to the "
                + "user go to the inbox). Params: goal (required — what the run should "
                + "think about, including the relevant findings of earlier runs, because "
                + "the tree reads ONLY the goal); availableRecipes (specialist recipes "
                + "the nodes may CALL_RECIPE, e.g. web-research, analyze, code-read, "
                + "quick-lookup); maxTreeNodes and maxTreeDepth (bounds). A live tree "
                + "must be stopped with marvin_stop first; after a terminal run the "
                + "restart is free. You are woken when the tree finishes — narrate the "
                + "result in the chat.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(
                "goal",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "What this run should think about — self-contained: the tree never "
                                + "sees the conversation, fold in whatever from earlier runs "
                                + "matters"));
        props.put(
                "availableRecipes",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Specialist recipes for CALL_RECIPE (default: the engine's configured set)"));
        props.put("maxTreeNodes", Map.of("type", "integer", "description", "Hard cap on tree size"));
        props.put("maxTreeDepth", Map.of("type", "integer", "description", "Cap on NEEDS_SUBTASKS depth"));
        return Map.of("type", "object", "properties", props, "required", List.of("goal"));
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of(ToolLabels.INTERNAL, "write", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) throws ToolException {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        String goal = stringParam(params, "goal");
        if (goal == null) {
            throw new ToolException("marvin_start requires a goal — the tree has nothing to "
                    + "think about without one. Fold the user's request (and relevant findings "
                    + "of earlier runs) into it.");
        }
        if (engine.treeIsLive(process)) {
            throw new ToolException("A tree run is already active — stop it with marvin_stop "
                    + "before starting a new one (a new start requires the previous run stopped).");
        }

        applyEngineParamOverrides(process, params);
        ThinkProcessDocument fresh = process(thinkProcessService, ctx);
        engine.startTree(fresh, goal);
        return Map.of(
                "started",
                true,
                "goal",
                goal,
                "note",
                "tree kicked — the node machine walks one phase per turn in the "
                        + "background; the todo list shows the open frontier; you are woken "
                        + "when the tree waits for a human answer and when it finishes");
    }

    /**
     * Persists the optional overrides on the process' engine params — the
     * same knobs {@code start()} would have read from the spawn. Vogon's
     * control keys are never overwritten; Marvin's own control keys
     * (sessionMode/chatIdentity) stay untouched.
     */
    private void applyEngineParamOverrides(ThinkProcessDocument process, Map<String, Object> params) {
        Map<String, Object> engineParams = process.getEngineParams();
        Map<String, Object> p = engineParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(engineParams);
        boolean dirty = false;

        List<String> availableRecipes = stringListParam(params, "availableRecipes");
        if (availableRecipes != null) {
            p.put("availableRecipes", availableRecipes);
            dirty = true;
        }
        Integer maxTreeNodes = intParam(params, "maxTreeNodes");
        if (maxTreeNodes != null) {
            p.put("maxTreeNodes", maxTreeNodes);
            dirty = true;
        }
        Integer maxTreeDepth = intParam(params, "maxTreeDepth");
        if (maxTreeDepth != null) {
            p.put("maxTreeDepth", maxTreeDepth);
            dirty = true;
        }

        if (dirty) {
            process.setEngineParams(p);
            thinkProcessService.replaceEngineParams(process.getId(), p);
        }
    }
}
