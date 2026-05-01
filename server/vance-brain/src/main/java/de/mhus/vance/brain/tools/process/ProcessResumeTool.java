package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resumes a paused (or system-suspended) worker think-process.
 * Status PAUSED → IDLE on the worker's lane, then a {@code runTurn}
 * is scheduled so any pending messages that piled up while paused
 * (e.g. user-correction nudges via {@code process_steer}) are
 * drained. Used by Arthur after the user has clarified.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessResumeTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Target process name in the current session.")),
            "required", List.of("name"));

    private final ThinkProcessService thinkProcessService;
    private final SessionLifecycleService sessionLifecycle;
    private final ProcessEventEmitter processEventEmitter;

    @Override
    public String name() {
        return "process_resume";
    }

    @Override
    public String description() {
        return "Resume a paused or suspended think-process in the "
                + "current session. The process transitions to IDLE "
                + "and any queued pending messages are drained "
                + "immediately. Pair with process_steer first if you "
                + "want to push correction info into the process "
                + "before it resumes.";
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
            throw new ToolException("process_resume requires a session scope");
        }
        Object rawName = params == null ? null : params.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required and must be a non-empty string");
        }
        ThinkProcessDocument target = thinkProcessService
                .findByName(ctx.tenantId(), sessionId, name)
                .orElseThrow(() -> new ToolException(
                        "Process '" + name + "' not found in current session"));
        sessionLifecycle.resumeProcess(target, processEventEmitter);
        ThinkProcessDocument refreshed = thinkProcessService.findById(target.getId())
                .orElse(target);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", refreshed.getName());
        out.put("status", refreshed.getStatus() == null ? null : refreshed.getStatus().name());
        return out;
    }
}
