package de.mhus.vance.brain.lunkwill.tools;

import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Removes TodoItems by id from the calling process's TodoList. Unknown
 * IDs are silently skipped — LLM-supplied stale references must not
 * crash the turn.
 *
 * <p>Schema is {@code ids: ["3","5"]} (not {@code items: [{id}]}) —
 * remove doesn't need a full item object. See
 * {@code specification/public/lunkwill-engine.md §9}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoRemoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "ids", Map.of(
                            "type", "array",
                            "description", "IDs of items to remove.",
                            "items", Map.of("type", "string"))),
            "required", List.of("ids"));

    private final ThinkProcessService thinkProcessService;
    private final PlanModeEventEmitter planModeEventEmitter;

    @Override
    public String name() {
        return "todo_remove";
    }

    @Override
    public String description() {
        return "Drop one or more TodoItems from the current process's "
                + "plan by id. Unknown ids are ignored.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public boolean contributesPrak() {
        return false;
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
            throw new ToolException("todo_remove requires a process scope");
        }
        Object rawIds = params == null ? null : params.get("ids");
        if (!(rawIds instanceof List<?> rawList)) {
            throw new ToolException("'ids' is required and must be a list of strings");
        }
        List<String> ids = new ArrayList<>();
        for (Object o : rawList) {
            if (o instanceof String s && !s.isBlank()) {
                ids.add(s);
            }
        }
        int removed = thinkProcessService.removeTodos(processId, ids);
        if (removed > 0) {
            thinkProcessService.findById(processId).ifPresent(refreshed ->
                    planModeEventEmitter.emitTodosUpdated(refreshed, refreshed.getTodos()));
        }
        log.info("todo_remove process='{}' requested={} removed={}",
                processId, ids.size(), removed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("removed", removed);
        return out;
    }
}
