package de.mhus.vance.brain.tools.process;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.StopInitiatorRegistry;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Stops a think-process in the current session. Used by an
 * orchestrator (Arthur) to terminate a worker that's no longer needed
 * — the symmetric counterpart to {@code process_create}.
 *
 * <p>Runs the engine's {@code stop} on the target's lane so the call
 * doesn't deadlock with the orchestrator's own lane and serialises
 * cleanly with whatever turn the worker is currently in. Self-stop
 * is allowed (a process can step itself off the queue), but the
 * caller is then responsible for not depending on its own state
 * afterwards.
 */
@Component
@Slf4j
public class ProcessStopTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Target process name in the current session.")),
            "required", List.of("name"));

    private final ThinkProcessService thinkProcessService;
    /** Lazy — same cycle reasons as {@code ProcessCreateTool}. */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final LaneScheduler laneScheduler;
    private final StopInitiatorRegistry stopInitiatorRegistry;

    public ProcessStopTool(
            ThinkProcessService thinkProcessService,
            ObjectProvider<ThinkEngineService> thinkEngineServiceProvider,
            LaneScheduler laneScheduler,
            StopInitiatorRegistry stopInitiatorRegistry) {
        this.thinkProcessService = thinkProcessService;
        this.thinkEngineServiceProvider = thinkEngineServiceProvider;
        this.laneScheduler = laneScheduler;
        this.stopInitiatorRegistry = stopInitiatorRegistry;
    }

    @Override
    public String name() {
        return "process_stop";
    }

    @Override
    public String description() {
        return "Stop a think-process in the current session. The engine's "
                + "stop hook runs and the process transitions to STOPPED. "
                + "Returns the final status.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String sessionId = ctx.sessionId();
        if (sessionId == null) {
            throw new ToolException("process_stop requires a session scope");
        }
        Object rawName = params == null ? null : params.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required and must be a non-empty string");
        }

        // Fall back to by-id lookup when the LLM passes the Mongo id
        // (which it sees in <process-event sourceProcessId="..."> markers)
        // instead of the process name. Scoped to the same session/tenant.
        ThinkProcessDocument target = thinkProcessService
                .findByName(ctx.tenantId(), sessionId, name)
                .or(() -> thinkProcessService.findById(name)
                        .filter(p -> ctx.tenantId().equals(p.getTenantId())
                                && sessionId.equals(p.getSessionId())))
                .orElseThrow(() -> new ToolException(
                        "Process '" + name + "' not found in current session"));

        // If already terminal, return its current shape — no engine call.
        ThinkProcessStatus current = target.getStatus();
        if (current == ThinkProcessStatus.CLOSED) {
            return shape(target);
        }

        // Mark the stop as parent-initiated so ParentNotificationListener
        // can suppress the resulting STOPPED event going BACK to this
        // caller — the caller already knows it stopped the child, no
        // need to wake them with a redundant inbox event. Without this
        // suppression Arthur sees the event in his pending queue, the
        // lane scheduler wakes him for an extra LLM turn, and he
        // sometimes interprets the event as "re-run the user's request"
        // — the spontaneous-restart bug.
        if (ctx.processId() != null) {
            stopInitiatorRegistry.mark(target.getId(), ctx.processId());
        }
        try {
            laneScheduler.submit(target.getId(),
                    () -> {
                        thinkEngineServiceProvider.getObject().stop(target);
                        return null;
                    }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted waiting for target stop");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new ToolException(
                    "Target stop failed: " + cause.getMessage(), cause);
        } finally {
            // Listener consumes on match; this is the cleanup safety net
            // for the case where engine.stop fails before the status
            // transition fires (no listener call → entry would otherwise
            // leak forever).
            stopInitiatorRegistry.consume(target.getId());
        }

        ThinkProcessDocument refreshed = thinkProcessService.findById(target.getId())
                .orElse(target);
        return shape(refreshed);
    }

    private static Map<String, Object> shape(ThinkProcessDocument d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", d.getName());
        out.put("status", d.getStatus() == null ? null : d.getStatus().name());
        return out;
    }
}
