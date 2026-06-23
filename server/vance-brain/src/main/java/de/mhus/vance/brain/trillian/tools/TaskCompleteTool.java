package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Used by the Trillian User worker to report that a task has been
 * completed successfully. Dispatches a {@code task_done} ProcessEvent
 * into the paired Control process's inbox so Control surfaces the
 * outcome to the human.
 */
@Component
@RequiredArgsConstructor
public class TaskCompleteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "taskId", Map.of(
                            "type", "string",
                            "description", "The taskId from the original task_request event."),
                    "result", Map.of(
                            "type", "string",
                            "description", "Short human-readable summary of what was achieved.")),
            "required", List.of("taskId", "result"));

    private final TrillianInternalApi api;

    @Override
    public String name() {
        return "task_complete";
    }

    @Override
    public String description() {
        return "Report a task as completed successfully. Sends a "
                + "task_done event to the paired Trillian Control "
                + "process so the human gets notified.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("task_complete requires a process scope");
        }
        Object rawTaskId = params == null ? null : params.get("taskId");
        Object rawResult = params == null ? null : params.get("result");
        if (!(rawTaskId instanceof String taskId) || taskId.isBlank()) {
            throw new ToolException("'taskId' is required and must be non-empty");
        }
        if (!(rawResult instanceof String result) || result.isBlank()) {
            throw new ToolException("'result' is required and must be non-empty");
        }
        Optional<ThinkProcessDocument> peerOpt = api.findPeer(ctx.processId());
        if (peerOpt.isEmpty()) {
            throw new ToolException(
                    "No Trillian Control peer found — this tool is only available "
                            + "inside a Trillian-User worker");
        }
        ThinkProcessDocument peer = peerOpt.get();
        Optional<String> eventId = api.dispatchTaskEvent(
                ctx.processId(),
                peer.getId(),
                TrillianInternalApi.TASK_EVENT_DONE,
                taskId,
                "Task done: " + truncate(result, 240),
                Map.of(TrillianInternalApi.PAYLOAD_KEY_RESULT, result));
        if (eventId.isEmpty()) {
            throw new ToolException("Failed to dispatch task_done event");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("status", "reported");
        out.put("eventId", eventId.get());
        return out;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
