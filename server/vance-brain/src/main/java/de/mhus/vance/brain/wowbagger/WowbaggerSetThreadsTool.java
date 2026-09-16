package de.mhus.vance.brain.wowbagger;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Halts the pool (stop) or changes the desired worker count (resize) — the
 * runtime knobs of the mechanical thread list (§4a.2). 0 threads = parked;
 * N > 0 = (re)starts rotation with N workers on the existing structure.
 */
@Component
@RequiredArgsConstructor
public class WowbaggerSetThreadsTool extends WowbaggerBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final WowbaggerPoolService pool;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "threads",
                            Map.of(
                                    "type",
                                    "integer",
                                    "description",
                                    "Desired worker-thread count (0–64). 0 = stop rotating "
                                            + "(the pool parks, the pointer and results stay).")),
            "required", java.util.List.of("threads"));

    @Override
    public String name() {
        return "wowbagger_set_threads";
    }

    @Override
    public String description() {
        return "Change the desired worker-thread count of the mechanical pool. "
                + "0 = parked, N > 0 = rotate with N workers (restarts a parked run). "
                + "This is not the total record count — it's the parallelism.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) throws ToolException {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        int threads = intParam(params, "threads", -1);
        if (threads < 0 || threads > 64) {
            throw new ToolException("'threads' must be between 0 and 64");
        }
        WowbaggerState s = pool.structure(process.getId());
        s.setThreadsDesired(threads);
        if (threads == 0) {
            pool.stop(process.getId());
            pool.persistStructure(process, s);
            return Map.of("threadsDesired", 0, "state", "pool parked — structure intact");
        }
        // Persist the desire first, then start — the runner syncs to it.
        pool.persistStructure(process, s);
        WowbaggerPoolService.RunView view;
        try {
            view = pool.start(process);
        } catch (java.io.IOException e) {
            // Requeue path: start() may fail on a lost source after park.
            throw new ToolException("cannot start the pool: " + e.getMessage(), e);
        }
        return Map.of("threadsDesired", threads, "threadsActive", view.threadsActive());
    }
}
