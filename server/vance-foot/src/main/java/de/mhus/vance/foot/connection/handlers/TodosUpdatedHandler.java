package de.mhus.vance.foot.connection.handlers;

import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.foot.connection.MessageHandler;
import de.mhus.vance.foot.ui.PlanModeState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles {@code todos-updated} notifications. Arthur emits this when
 * the TodoList is set fresh ({@code PROPOSE_PLAN}) or items change
 * status ({@code TODO_UPDATE}).
 *
 * <p>The list is routed into {@link PlanModeState} which feeds the
 * {@link de.mhus.vance.foot.ui.StatusBar} persistent panel above the
 * prompt — see {@code readme/arthur-plan-mode.md} §10. No scrollback
 * print: the panel is the source of truth, repeated re-renders in the
 * scrollback would just be noise.
 */
@Component
public class TodosUpdatedHandler implements MessageHandler {

    private final PlanModeState planMode;
    private final ObjectMapper json = JsonMapper.builder().build();

    public TodosUpdatedHandler(PlanModeState planMode) {
        this.planMode = planMode;
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
        planMode.setTodos(name, msg.getTodos());
    }
}
