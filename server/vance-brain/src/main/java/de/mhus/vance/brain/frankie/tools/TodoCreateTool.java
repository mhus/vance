package de.mhus.vance.brain.frankie.tools;

import de.mhus.vance.api.thinkprocess.TodoItem;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Appends TodoItems to the calling process's TodoList. IDs are
 * server-assigned (sequential, max-of-existing + 1, never reused)
 * so the LLM only supplies content + optional activeForm. The
 * assigned IDs appear in the per-turn prompt block as
 * {@code (id=N)} markers — that's where the LLM picks them up for
 * subsequent {@code todo_update} / {@code todo_remove} calls.
 *
 * <p>Status is always {@code PENDING} on create; transitions go
 * through {@code todo_update}. CRUD-clean — see
 * {@code specification/public/frankie-engine.md §9}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoCreateTool implements Tool {

    private static final Map<String, Object> ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "content", Map.of(
                            "type", "string",
                            "description", "Imperative form, e.g. 'Migrate token storage'."),
                    "activeForm", Map.of(
                            "type", "string",
                            "description", "Optional present-continuous form for UI spinner.")),
            "required", List.of("content"));

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "items", Map.of(
                            "type", "array",
                            "description", "New items to append. IDs are server-assigned and "
                                    + "appear in the prompt block after creation.",
                            "items", ITEM_SCHEMA)),
            "required", List.of("items"));

    private final ThinkProcessService thinkProcessService;
    private final PlanModeEventEmitter planModeEventEmitter;

    @Override
    public String name() {
        return "todo_create";
    }

    @Override
    public String description() {
        return "Append one or more TodoItems to the current process's plan. "
                + "IDs are assigned by the server and shown in the prompt "
                + "block — use them with todo_update / todo_remove. "
                + "Items: [{content, activeForm?}].";
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
            throw new ToolException("todo_create requires a process scope");
        }
        Object rawItems = params == null ? null : params.get("items");
        if (!(rawItems instanceof List<?> rawList)) {
            throw new ToolException("'items' is required and must be a list");
        }
        List<TodoItem> input = parseItems(rawList);
        if (input.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("created", List.of());
            out.put("note", "no parseable items — nothing created");
            return out;
        }

        boolean wasEmptyBefore = thinkProcessService.findById(processId)
                .map(p -> p.getTodos() == null || p.getTodos().isEmpty())
                .orElse(true);

        List<TodoItem> assigned = thinkProcessService.addTodos(processId, input);
        if (assigned == null) {
            throw new ToolException("process not found or write conflict: " + processId);
        }

        thinkProcessService.findById(processId).ifPresent(refreshed -> {
            planModeEventEmitter.emitTodosUpdated(refreshed, refreshed.getTodos());
            if (wasEmptyBefore) {
                // First items in the list — drop the "plan exists now" hint
                // so Foot / Web-UI can show the banner. planVersion stays
                // at 1 since Frankie doesn't track revisions.
                planModeEventEmitter.emitPlanProposed(
                        refreshed, /*summary*/ null, /*planVersion*/ 1);
            }
        });

        log.info("todo_create process='{}' added={}", processId, assigned.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("created", assigned.stream().map(TodoCreateTool::shapeItem).toList());
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<TodoItem> parseItems(List<?> rawList) {
        List<TodoItem> out = new ArrayList<>();
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> m = (Map<String, Object>) rawMap;
            String content = stringOrNull(m.get("content"));
            if (content == null || content.isBlank()) {
                continue;
            }
            String activeForm = stringOrNull(m.get("activeForm"));
            out.add(TodoItem.builder()
                    .content(content)
                    .activeForm(activeForm)
                    .build());
        }
        return out;
    }

    private static Map<String, Object> shapeItem(TodoItem t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("content", t.getContent());
        if (t.getActiveForm() != null) {
            m.put("activeForm", t.getActiveForm());
        }
        return m;
    }

    private static @Nullable String stringOrNull(@Nullable Object o) {
        return o instanceof String s ? s : null;
    }
}
