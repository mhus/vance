package de.mhus.vance.brain.hactar;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Stops the live run — the F2 prerequisite for a re-kick. Interrupts the
 * runner thread: between phases the loop exits cleanly, mid-EXECUTING the
 * script executor cancels its watchdog future and force-closes the GraalJS
 * context (a cancelled script may leave partial side effects, exactly like a
 * timeout — the failure reason names it). Idempotent: stopping a run that
 * already ended reports {@code stopped: false} with the terminal state.
 */
@Component
@RequiredArgsConstructor
public class HactarStopTool extends HactarBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final HactarRunService runService;

    @Override
    public String name() {
        return "hactar_stop";
    }

    @Override
    public String description() {
        return "Stop the live run. The runner exits between phases, or the script is "
                + "cancelled mid-run (its watchdog future is cancelled and the GraalJS "
                + "context force-closed — partial side effects are possible, like a "
                + "timeout). A new run can be started with hactar_start afterwards. "
                + "Idempotent.";
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public java.util.Set<String> labels() {
        return java.util.Set.of("write", "side-effect");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        ThinkProcessDocument process = process(thinkProcessService, ctx);
        boolean stopped = runService.stop(process.getId());
        return Map.of(
                "stopped",
                stopped,
                "note",
                stopped
                        ? "stop requested — the runner exits at the next phase boundary or "
                                + "cancels the script mid-run; you will be woken with the terminal note"
                        : "no live run — nothing to stop");
    }
}
