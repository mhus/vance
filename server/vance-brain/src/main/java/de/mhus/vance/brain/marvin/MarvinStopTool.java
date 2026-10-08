package de.mhus.vance.brain.marvin;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stops the live tree — the F2 prerequisite for a re-kick. Running and
 * waiting nodes are marked FAILED (reason "stopped by request"), pending
 * frontier nodes SKIPPED, spawned child processes are stopped, and open
 * inbox questions are resolved UNDECIDABLE so nothing keeps nagging the
 * human. Partial results stay partial — the tool result says so, the
 * identity passes it on.
 *
 * <p>Deliberately does NOT close the process — the identity survives its
 * runs (decision F4); the engine narrates the stop as a {@code [tree]} note
 * and takes the next goal. Session/process close is the engine's
 * {@code stop}, not this tool.
 *
 * <p>Lane note: the identity turn that calls this tool waits for the
 * current node phase to finish — tree steps and identity turns serialize
 * on the same lane (planning §3.2). "Stop" is prompt, not instant, while a
 * phase (or its inline CALL_RECIPE sub-drive) is in flight.
 */
@Component
public class MarvinStopTool extends MarvinBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final MarvinEngine engine;

    public MarvinStopTool(ThinkProcessService thinkProcessService, MarvinEngine engine) {
        this.thinkProcessService = thinkProcessService;
        this.engine = engine;
    }

    @Override
    public String name() {
        return "marvin_stop";
    }

    @Override
    public String description() {
        return "Stop the live tree run. Running/waiting nodes are marked stopped, spawned "
                + "worker processes are stopped, and open inbox questions are resolved "
                + "(undecidable) so they stop nagging. Partial results stay partial — say "
                + "so. The process stays open — a new run can be started with marvin_start "
                + "afterwards. Idempotent: stopping when no live tree exists reports "
                + "stopped: false.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of(ToolLabels.INTERNAL, "write", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        boolean stopped = engine.stopTree(process);
        return Map.of(
                "stopped",
                stopped,
                "note",
                stopped
                        ? "tree stopped — running work was interrupted, whatever the nodes "
                                + "produced so far is partial; you will be woken with the "
                                + "terminal note"
                        : "no live tree — nothing to stop");
    }
}
