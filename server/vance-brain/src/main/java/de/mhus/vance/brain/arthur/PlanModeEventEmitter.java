package de.mhus.vance.brain.arthur;

import de.mhus.vance.api.thinkprocess.PlanProposedNotification;
import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.ProcessModeChangedNotification;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Single emit-path for the three Plan-Mode WebSocket notifications:
 * {@code process-mode-changed}, {@code todos-updated}, and
 * {@code plan-proposed}. ArthurEngine's action handlers funnel
 * through here so the message envelopes stay consistent.
 *
 * <p>See {@code readme/arthur-plan-mode.md} §9.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanModeEventEmitter {

    private final ClientEventPublisher events;

    public void emitModeChanged(
            ThinkProcessDocument process, ProcessMode oldMode, ProcessMode newMode) {
        if (process.getSessionId() == null || process.getSessionId().isBlank()) {
            return;
        }
        ProcessModeChangedNotification msg = ProcessModeChangedNotification.builder()
                .processId(process.getId() == null ? "" : process.getId())
                .processName(process.getName())
                .sessionId(process.getSessionId())
                .oldMode(oldMode)
                .newMode(newMode)
                .build();
        events.publish(process.getSessionId(), MessageType.PROCESS_MODE_CHANGED, msg);
    }

    public void emitTodosUpdated(ThinkProcessDocument process, List<TodoItem> todos) {
        if (process.getSessionId() == null || process.getSessionId().isBlank()) {
            return;
        }
        TodosUpdatedNotification msg = TodosUpdatedNotification.builder()
                .processId(process.getId() == null ? "" : process.getId())
                .processName(process.getName())
                .sessionId(process.getSessionId())
                .todos(todos == null ? List.of() : List.copyOf(todos))
                .build();
        events.publish(process.getSessionId(), MessageType.TODOS_UPDATED, msg);
    }

    public void emitPlanProposed(
            ThinkProcessDocument process, @Nullable String summary, int planVersion) {
        if (process.getSessionId() == null || process.getSessionId().isBlank()) {
            return;
        }
        PlanProposedNotification msg = PlanProposedNotification.builder()
                .processId(process.getId() == null ? "" : process.getId())
                .processName(process.getName())
                .sessionId(process.getSessionId())
                .summary(summary)
                .planVersion(planVersion)
                .build();
        events.publish(process.getSessionId(), MessageType.PLAN_PROPOSED, msg);
    }
}
