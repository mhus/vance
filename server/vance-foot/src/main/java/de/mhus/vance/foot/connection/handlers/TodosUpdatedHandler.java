package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PlanModeState;
import de.mhus.vance.foot.ui.StreamingDisplay;
import de.mhus.vance.foot.ui.Verbosity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jline.utils.AttributedStyle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles {@code todos-updated} notifications. Engines emit this when
 * the TodoList is set fresh (Arthur {@code PROPOSE_PLAN}, Frankie
 * {@code todo_create}) or items change status.
 *
 * <p>The list is routed into {@link PlanModeState} (for the prompt-tag
 * consumer) and rendered as a box in the scrollback so the user
 * actually sees the plan. The persistent panel that {@link PlanModeState}
 * used to feed was removed in the render-free {@code StatusBar} refactor
 * (see {@code readme/foot-status-bar-rendering.md}) without a
 * replacement, which left engine-produced todos invisible on the CLI —
 * see {@code readme/foot-todo-rendering.md}. Frankie never emits a
 * {@code process-mode-changed}, so a mode-gated panel would never have
 * surfaced its plan anyway; a scrollback print is engine-agnostic.
 *
 * <p>Redundant frames (same content + status) are suppressed so a burst
 * of status-only updates does not spam the scrollback.
 */
@Component
public class TodosUpdatedHandler implements MessageHandler {

    private static final AttributedStyle BOX_STYLE = AttributedStyle.DEFAULT
            .foreground(AttributedStyle.CYAN);

    private final PlanModeState planMode;
    private final ChatTerminal terminal;
    private final StreamingDisplay streaming;
    private final ObjectMapper json = JsonMapper.builder().build();

    /** Last rendered signature per process — dedup against redundant frames. */
    private final Map<String, String> lastRendered = new ConcurrentHashMap<>();

    public TodosUpdatedHandler(PlanModeState planMode,
                               ChatTerminal terminal,
                               StreamingDisplay streaming) {
        this.planMode = planMode;
        this.terminal = terminal;
        this.streaming = streaming;
    }

    @Override
    public String messageType() {
        return MessageType.TODOS_UPDATED;
    }

    @Override
    public void handle(WebSocketEnvelope envelope) {
        TodosUpdatedNotification msg = json.convertValue(
                envelope.getData(), TodosUpdatedNotification.class);
        if (msg == null) return;

        String name = msg.getProcessName() == null || msg.getProcessName().isBlank()
                ? "process"
                : msg.getProcessName();
        List<TodoItem> todos = msg.getTodos();
        planMode.setTodos(name, todos);

        if (todos == null || todos.isEmpty()) {
            lastRendered.remove(name);
            return;
        }

        String signature = signature(todos);
        // Suppress no-op repeats: engines re-emit the full list on every
        // status change, so an unchanged list must not reprint.
        if (signature.equals(lastRendered.get(name))) {
            return;
        }
        lastRendered.put(name, signature);

        streaming.suspend();
        terminal.printBoxed(Verbosity.INFO, BOX_STYLE, renderLines(name, todos));
    }

    static List<String> renderLines(String name, List<TodoItem> todos) {
        List<String> lines = new ArrayList<>(todos.size() + 1);
        lines.add("Plan — " + name);
        for (TodoItem item : todos) {
            String text = item.getContent() == null || item.getContent().isBlank()
                    ? (item.getActiveForm() == null ? "" : item.getActiveForm())
                    : item.getContent();
            lines.add(glyph(item.getStatus()) + " " + text);
        }
        return lines;
    }

    static String glyph(@org.jspecify.annotations.Nullable TodoStatus status) {
        if (status == null) return "○";
        return switch (status) {
            case COMPLETED -> "✓";
            case IN_PROGRESS -> "◐";
            case PENDING -> "○";
        };
    }

    static String signature(List<TodoItem> todos) {
        StringBuilder sb = new StringBuilder();
        for (TodoItem item : todos) {
            sb.append(item.getStatus()).append(':').append(item.getContent()).append('\n');
        }
        return sb.toString();
    }
}
