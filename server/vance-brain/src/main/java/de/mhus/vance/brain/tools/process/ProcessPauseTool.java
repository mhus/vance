package de.mhus.vance.brain.tools.process;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pauses a worker think-process. The worker transitions to
 * {@code PAUSED} at the next safe boundary (lane-serialised). New
 * pending messages are still appended to its inbox but do not
 * auto-wake it — only an explicit {@code process_resume} (or the
 * symmetric WS handler) lets it run again.
 *
 * <p>Used by Arthur when it decides to halt a worker without
 * killing it. The user-driven counterpart (foot ESC, {@code /stop})
 * takes the {@code process-pause} WS path with no name and pauses
 * all active workers under the chat-process.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessPauseTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Target process name in the current session.")),
            "required", List.of("name"));

    private final ThinkProcessService thinkProcessService;
    private final LaneScheduler laneScheduler;

    @Override
    public String name() {
        return "process_pause";
    }

    @Override
    public String description() {
        return "Pause a think-process in the current session. The "
                + "process transitions to PAUSED at the next safe "
                + "boundary; pending messages still queue but do not "
                + "wake it. Use process_resume to wake it back up.";
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
            throw new ToolException("process_pause requires a session scope");
        }
        Object rawName = params == null ? null : params.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required and must be a non-empty string");
        }
        ThinkProcessDocument target = thinkProcessService
                .findByName(ctx.tenantId(), sessionId, name)
                .orElseThrow(() -> new ToolException(
                        "Process '" + name + "' not found in current session"));

        ThinkProcessStatus current = target.getStatus();
        if (current == ThinkProcessStatus.CLOSED
                || current == ThinkProcessStatus.PAUSED) {
            return shape(target);
        }
        try {
            laneScheduler.submit(target.getId(), () -> {
                thinkProcessService.updateStatus(target.getId(), ThinkProcessStatus.PAUSED);
                return null;
            }).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted waiting for pause");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new ToolException("Pause failed: " + cause.getMessage(), cause);
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
