package de.mhus.vance.brain.lunkwill.tools;

import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Per-item status update for the calling process's TodoList. Used by
 * Lunkwill workers to mark items {@code IN_PROGRESS} when picking up a
 * step and {@code COMPLETED} when finishing. Items not in the request
 * stay untouched.
 *
 * <p>Delegates to {@link ThinkProcessService#updateTodoStatuses} which
 * applies the update under optimistic locking with retry. Items
 * referenced by id but not present in the document are silently
 * ignored — keeps the tool forgiving of LLM hallucinations of step
 * ids without aborting the turn.
 *
 * <p>Unlike the {@code TODO_UPDATE} action in the full Plan-Mode flow,
 * this tool emits no Mode transition and no history-tag marker — Lunkwill
 * is mode-less by design (see {@code specification/lunkwill-engine.md §9}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoUpdateTool implements Tool {

    private static final Map<String, Object> UPDATE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Id of the existing TodoItem."),
                    "status", Map.of(
                            "type", "string",
                            "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED"),
                            "description", "New status.")),
            "required", List.of("id", "status"));

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "updates", Map.of(
                            "type", "array",
                            "description", "List of {id, status} entries. Items not "
                                    + "in the list are left unchanged. Items in the list "
                                    + "but missing from the document are ignored.",
                            "items", UPDATE_SCHEMA)),
            "required", List.of("updates"));

    private final ThinkProcessService thinkProcessService;
    private final PlanModeEventEmitter planModeEventEmitter;

    @Override
    public String name() {
        return "todo_update";
    }

    @Override
    public String description() {
        return "Update the status of one or more TodoItems on the current "
                + "process. Convention: set IN_PROGRESS when picking up a step, "
                + "COMPLETED when done. Never downgrade a COMPLETED item. "
                + "Updates: [{id, status: PENDING|IN_PROGRESS|COMPLETED}].";
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
        return Set.of("write");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String processId = ctx.processId();
        if (processId == null || processId.isBlank()) {
            throw new ToolException("todo_update requires a process scope");
        }
        Object rawUpdates = params == null ? null : params.get("updates");
        if (!(rawUpdates instanceof List<?> rawList)) {
            throw new ToolException("'updates' is required and must be a list");
        }
        Map<String, TodoStatus> updates = parseUpdates(rawList);
        if (updates.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("applied", 0);
            out.put("note", "no parseable updates — nothing changed");
            return out;
        }
        boolean changed = thinkProcessService.updateTodoStatuses(processId, updates);
        if (changed) {
            thinkProcessService.findById(processId).ifPresent(refreshed -> {
                planModeEventEmitter.emitTodosUpdated(refreshed, refreshed.getTodos());
            });
        }
        log.info("todo_update process='{}' updates={} changed={}",
                processId, updates.size(), changed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("applied", updates.size());
        out.put("changed", changed);
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, TodoStatus> parseUpdates(List<?> rawList) {
        Map<String, TodoStatus> out = new LinkedHashMap<>();
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> m = (Map<String, Object>) rawMap;
            String id = stringOrNull(m.get("id"));
            String statusStr = stringOrNull(m.get("status"));
            if (id == null || id.isBlank() || statusStr == null || statusStr.isBlank()) {
                continue;
            }
            try {
                out.put(id, TodoStatus.valueOf(statusStr));
            } catch (IllegalArgumentException ignored) {
                // unknown status — skip
            }
        }
        return out;
    }

    private static @Nullable String stringOrNull(@Nullable Object o) {
        return o instanceof String s ? s : null;
    }
}
