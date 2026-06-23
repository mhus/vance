package de.mhus.vance.brain.lunkwill.tools;

import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
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
 * Replaces the calling process's TodoList. Lunkwill's reduced
 * Plan-Mode variant uses this tool to seed an initial plan and to
 * rewrite the list when the structure changes mid-task — see
 * {@code specification/lunkwill-engine.md §9}.
 *
 * <p>Status semantics mirror the full Plan-Mode pipeline so the same
 * persistence layer ({@link ThinkProcessService#setTodos}) and the
 * same {@code todos-updated} / {@code plan-proposed} WebSocket
 * envelopes ({@link PlanModeEventEmitter}) can be reused without
 * duplication. The difference to {@code PROPOSE_PLAN} is that there is
 * no Mode switch, no approval gate, no read-only tool filter — the
 * worker writes a plan and immediately works on it.
 *
 * <p>Items missing {@code id} or {@code content} are silently
 * dropped. An LLM mis-shape doesn't abort the turn; the worst case is
 * an under-populated TodoList that the LLM can re-emit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoWriteTool implements Tool {

    private static final Map<String, Object> ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "id", Map.of(
                            "type", "string",
                            "description", "Stable id within the process. Survives status updates and edits."),
                    "content", Map.of(
                            "type", "string",
                            "description", "Imperative form, e.g. 'Migrate token storage'."),
                    "activeForm", Map.of(
                            "type", "string",
                            "description", "Optional present-continuous form for spinner/UI."),
                    "status", Map.of(
                            "type", "string",
                            "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED"),
                            "description", "Initial status — defaults to PENDING.")),
            "required", List.of("id", "content"));

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "items", Map.of(
                            "type", "array",
                            "description", "Full replacement list. Empty array clears the TodoList.",
                            "items", ITEM_SCHEMA)),
            "required", List.of("items"));

    private final ThinkProcessService thinkProcessService;
    private final PlanModeEventEmitter planModeEventEmitter;

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public String description() {
        return "Replace the current process's TodoList. Use at the start of a "
                + "multi-step task to lay out a plan, or mid-task to restructure "
                + "when the original plan no longer fits. 3-8 items, logical "
                + "phases (not atomic tool-calls). Items: {id, content, activeForm?, "
                + "status?}.";
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
            throw new ToolException("todo_write requires a process scope");
        }
        Object rawItems = params == null ? null : params.get("items");
        if (!(rawItems instanceof List<?> rawList)) {
            throw new ToolException("'items' is required and must be a list");
        }
        List<TodoItem> todos = parseItems(rawList);
        boolean ok = thinkProcessService.setTodos(processId, todos);
        if (!ok) {
            throw new ToolException("process not found: " + processId);
        }
        ThinkProcessDocument refreshed = thinkProcessService.findById(processId)
                .orElseThrow(() -> new ToolException(
                        "process disappeared mid-write: " + processId));
        // Refresh the in-memory todos so a follow-up tool in the same
        // batch sees the new list. ThinkProcessDocument is the document
        // bean ThinkProcessService.findById returned — safe to mutate.
        refreshed.setTodos(todos);
        planModeEventEmitter.emitTodosUpdated(refreshed, todos);
        // plan-proposed is the "a plan exists now" hint Foot / Web-UI
        // use to drop the banner. planVersion stays at 1 for Lunkwill —
        // unlike Arthur's PROPOSE_PLAN we don't track revisions, the
        // chat history has the audit trail.
        planModeEventEmitter.emitPlanProposed(refreshed, /*summary*/ null, /*planVersion*/ 1);

        log.info("todo_write process='{}' count={}", processId, todos.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", todos.size());
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<TodoItem> parseItems(List<?> rawList) {
        List<TodoItem> out = new ArrayList<>();
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> m = (Map<String, Object>) rawMap;
            String id = stringOrNull(m.get("id"));
            String content = stringOrNull(m.get("content"));
            if (id == null || id.isBlank() || content == null || content.isBlank()) {
                continue;
            }
            String activeForm = stringOrNull(m.get("activeForm"));
            TodoStatus status = TodoStatus.PENDING;
            String statusStr = stringOrNull(m.get("status"));
            if (statusStr != null && !statusStr.isBlank()) {
                try {
                    status = TodoStatus.valueOf(statusStr);
                } catch (IllegalArgumentException ignored) {
                    // unknown status — keep PENDING default
                }
            }
            out.add(TodoItem.builder()
                    .id(id)
                    .status(status)
                    .content(content)
                    .activeForm(activeForm)
                    .build());
        }
        return out;
    }

    private static @Nullable String stringOrNull(@Nullable Object o) {
        return o instanceof String s ? s : null;
    }
}
