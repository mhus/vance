package de.mhus.vance.brain.wowbagger;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Stops the rotation — the park knob the prompt, the engine javadoc and
 * the status block's "User controls" line all name. Semantics are
 * {@link WowbaggerPoolService#stop(String)}: workers drain their current
 * chunk (never interrupted), state, pointer and published chunk docs
 * survive; a later {@code wowbagger_start} resumes exactly there.
 * Idempotent — stopping a parked pool is a no-op reporting the same
 * state, which is why the response carries the pool's view rather than
 * a bare "stopped".
 */
@Component
@RequiredArgsConstructor
public class WowbaggerStopTool extends WowbaggerBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final WowbaggerPoolService pool;

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of());

    @Override
    public String name() {
        return "wowbagger_stop";
    }

    @Override
    public String description() {
        return "Stop the worker pool. In-flight workers drain their current chunk, then "
                + "park; state, pointer and published chunk docs survive — a later "
                + "wowbagger_start resumes exactly there. Idempotent.";
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
        WowbaggerPoolService.RunView view = pool.stop(process.getId());
        return Map.of(
                "stopped",
                true,
                "running",
                view.running(),
                "threadsDesired",
                view.structure().getThreadsDesired(),
                "note",
                "pool parked — structure, pointer and published chunks are intact; "
                        + "wowbagger_start resumes, wowbagger_set_threads N rotates again");
    }
}
