package de.mhus.vance.brain.frankie.tools;

import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.TodoPatch;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Per-item full-field mutate for the calling process's TodoList. The
 * LLM passes a list of partial items keyed by {@code id}; any non-null
 * field overwrites the persisted value, null fields are left alone.
 *
 * <p>Auto-clear: if, after the update, every persisted item is in
 * status {@code COMPLETED}, the list is cleared entirely and a final
 * {@code todos-updated} carrying an empty list is emitted. Keeps the
 * per-turn prompt block from carrying a fully-done plan around forever
 * — see {@code specification/public/frankie-engine.md §9}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoUpdateTool implements Tool {

    private static final Map<String, Object> ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Id of the existing TodoItem."),
                    "status", Map.of(
                            "type", "string",
                            "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED"),
                            "description", "Optional new status."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Optional new content (imperative form)."),
                    "activeForm", Map.of(
                            "type", "string",
                            "description", "Optional new present-continuous form.")),
            "required", List.of("id"));

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "items", Map.of(
                            "type", "array",
                            "description", "Per-item partial mutate. id required, other fields optional.",
                            "items", ITEM_SCHEMA)),
            "required", List.of("items"));

    private final ThinkProcessService thinkProcessService;
    private final PlanModeEventEmitter planModeEventEmitter;

    @Override
    public String name() {
        return "todo_update";
    }

    @Override
    public String description() {
        return "Update one or more TodoItems on the current process. "
                + "id is required; status / content / activeForm are "
                + "optional and only overwrite when present. Convention: "
                + "set IN_PROGRESS when picking up a step, COMPLETED when "
                + "done. When every item becomes COMPLETED the list is "
                + "auto-cleared.";
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
            throw new ToolException("todo_update requires a process scope");
        }
        Object rawItems = params == null ? null : params.get("items");
        if (!(rawItems instanceof List<?> rawList)) {
            throw new ToolException("'items' is required and must be a list");
        }
        List<TodoPatch> patches = parsePatches(rawList);
        if (patches.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("applied", 0);
            out.put("note", "no parseable items — nothing changed");
            return out;
        }
        boolean changed = thinkProcessService.updateTodos(processId, patches);
        boolean cleared = false;
        if (changed) {
            cleared = maybeAutoClear(processId);
            if (!cleared) {
                thinkProcessService.findById(processId).ifPresent(refreshed ->
                        planModeEventEmitter.emitTodosUpdated(refreshed, refreshed.getTodos()));
            }
        }
        log.info("todo_update process='{}' applied={} changed={} cleared={}",
                processId, patches.size(), changed, cleared);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("applied", patches.size());
        out.put("changed", changed);
        out.put("cleared", cleared);
        return out;
    }

    /**
     * If every item is in {@code COMPLETED}, replaces the list with an
     * empty one and emits a final {@code todos-updated} with that empty
     * list. Returns {@code true} if the clear actually happened.
     */
    private boolean maybeAutoClear(String processId) {
        ThinkProcessDocument doc = thinkProcessService.findById(processId).orElse(null);
        if (doc == null) return false;
        List<TodoItem> todos = doc.getTodos();
        if (todos == null || todos.isEmpty()) return false;
        for (TodoItem t : todos) {
            if (t.getStatus() != TodoStatus.COMPLETED) return false;
        }
        thinkProcessService.setTodos(processId, List.of());
        doc.setTodos(List.of());
        planModeEventEmitter.emitTodosUpdated(doc, List.of());
        return true;
    }

    @SuppressWarnings("unchecked")
    static List<TodoPatch> parsePatches(List<?> rawList) {
        List<TodoPatch> out = new ArrayList<>();
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> m = (Map<String, Object>) rawMap;
            String id = stringOrNull(m.get("id"));
            if (id == null || id.isBlank()) continue;
            TodoStatus status = null;
            String statusStr = stringOrNull(m.get("status"));
            if (statusStr != null && !statusStr.isBlank()) {
                try {
                    status = TodoStatus.valueOf(statusStr);
                } catch (IllegalArgumentException ignored) {
                    // unknown status — skip the field, keep the patch (other fields may still apply)
                }
            }
            String content = stringOrNull(m.get("content"));
            String activeForm = stringOrNull(m.get("activeForm"));
            out.add(new TodoPatch(id, status, content, activeForm));
        }
        return out;
    }

    private static @Nullable String stringOrNull(@Nullable Object o) {
        return o instanceof String s ? s : null;
    }
}
