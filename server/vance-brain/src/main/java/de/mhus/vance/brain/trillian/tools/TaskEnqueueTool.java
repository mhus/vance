package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianControlEngine;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Enqueues a refined task into the Trillian User process's inbox.
 *
 * <p>Exposed via {@code trillian-control}'s {@code allowedToolsAdd}.
 * The LLM is instructed (recipe prompt) to confirm the task line
 * with the human before calling this — discipline lives in the
 * prompt, not in this tool.
 *
 * <p>Returns the generated {@code taskId} so the LLM (and the human
 * later, in {@code user_status} output) can correlate
 * {@code task_done} / {@code task_failed} replies.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskEnqueueTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "description", Map.of(
                            "type", "string",
                            "description", "One-line task statement. Should already be "
                                    + "confirmed with the human before calling this.")),
            "required", List.of("description"));

    private final TrillianInternalApi api;

    @Override
    public String name() {
        return "task_enqueue";
    }

    @Override
    public String description() {
        return "Push a task into the paired Trillian User worker's "
                + "inbox. Use after confirming the task line with the "
                + "human. Returns a taskId you can reference later.";
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
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("task_enqueue requires a process scope");
        }
        Object rawDesc = params == null ? null : params.get("description");
        if (!(rawDesc instanceof String description) || description.isBlank()) {
            throw new ToolException("'description' is required and must be a non-empty string");
        }
        Optional<ThinkProcessDocument> peerOpt = api.findPeer(ctx.processId());
        if (peerOpt.isEmpty()) {
            throw new ToolException(
                    "No Trillian User peer process found — this tool is only available "
                            + "inside a Trillian-Control session");
        }
        ThinkProcessDocument peer = peerOpt.get();
        String taskId = UUID.randomUUID().toString();
        String humanSummary = "Task request: " + truncate(description, 240);

        Optional<String> eventId = api.dispatchTaskEvent(
                ctx.processId(),
                peer.getId(),
                TrillianInternalApi.TASK_EVENT_REQUEST,
                taskId,
                humanSummary,
                Map.of(TrillianInternalApi.PAYLOAD_KEY_DESCRIPTION, description));
        if (eventId.isEmpty()) {
            throw new ToolException(
                    "Failed to dispatch task to Trillian User — see brain logs for detail");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("status", "queued");
        out.put("peerProcessName", peer.getName());
        out.put("eventId", eventId.get());
        return out;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
