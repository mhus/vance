package de.mhus.vance.brain.vogon;

import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stops the live run — the F2 prerequisite for a re-kick. The runner unwinds
 * its claimed tasks (a worker mid-write may leave partial side effects, like
 * any interruption — the terminal report names it, the identity passes it
 * on). Idempotent: stopping a run that already ended reports
 * {@code stopped: false}.
 *
 * <p>Deliberately does NOT close the process even in the chat form — the
 * identity survives its run (decision F4); the runner's terminal event wakes
 * it with the stop note. Session/process close is the engine's {@code stop},
 * not this tool.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
public class VogonStopTool extends VogonBaseTool {

    private final ThinkProcessService thinkProcessService;
    private final MagratheaWorkflowService workflowService;

    public VogonStopTool(ThinkProcessService thinkProcessService, MagratheaWorkflowService workflowService) {
        this.thinkProcessService = thinkProcessService;
        this.workflowService = workflowService;
    }

    @Override
    public String name() {
        return "vogon_stop";
    }

    @Override
    public String description() {
        return "Stop the live plan run. The runner unwinds its claimed tasks; a worker that was "
                + "mid-write may leave partial side effects (the terminal note names them). The "
                + "process stays open — a new run can be started with vogon_start afterwards, and "
                + "you are woken with the stop note. Idempotent.";
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
        String runId = runId(process);
        boolean stopped = false;
        if (runId != null) {
            stopped = workflowService.stopRun(
                    process.getTenantId(), process.getProjectId(), runId, "stopped by vogon_stop");
        }
        return Map.of(
                "stopped",
                stopped,
                "note",
                stopped
                        ? "stop requested — the runner unwinds its tasks; you will be woken with " + "the terminal note"
                        : "no live run — nothing to stop");
    }
}
