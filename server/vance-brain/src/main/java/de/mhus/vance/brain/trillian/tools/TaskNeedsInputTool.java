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
 * Used by the Trillian User worker to escalate: it needs more input
 * from the human before it can finish the task. Dispatches a
 * {@code task_needs_input} ProcessEvent to Control so Control can
 * ask the human.
 */
@Component
@RequiredArgsConstructor
public class TaskNeedsInputTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "taskId", Map.of(
                            "type", "string",
                            "description", "The taskId from the original task_request event."),
                    "question", Map.of(
                            "type", "string",
                            "description", "The clarification question for the human.")),
            "required", List.of("taskId", "question"));

    private final TrillianInternalApi api;

    @Override
    public String name() {
        return "task_needs_input";
    }

    @Override
    public String description() {
        return "Escalate a task to the human via Control because more "
                + "information is needed. Sends a task_needs_input event "
                + "carrying the clarification question.";
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
            throw new ToolException("task_needs_input requires a process scope");
        }
        Object rawTaskId = params == null ? null : params.get("taskId");
        Object rawQuestion = params == null ? null : params.get("question");
        if (!(rawTaskId instanceof String taskId) || taskId.isBlank()) {
            throw new ToolException("'taskId' is required and must be non-empty");
        }
        if (!(rawQuestion instanceof String question) || question.isBlank()) {
            throw new ToolException("'question' is required and must be non-empty");
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
                TrillianInternalApi.TASK_EVENT_NEEDS_INPUT,
                taskId,
                "Task needs input: " + truncate(question, 240),
                Map.of("question", question));
        if (eventId.isEmpty()) {
            throw new ToolException("Failed to dispatch task_needs_input event");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("status", "escalated");
        out.put("eventId", eventId.get());
        return out;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
