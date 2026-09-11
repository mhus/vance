package de.mhus.vance.brain.progress;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.ProcessModeChangedNotification;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Restores the plan/todo UI state on a freshly bound connection: a
 * reconnecting client must see the current plan without waiting for the
 * next engine mutation. Pushed once per session-bind (create / resume /
 * bootstrap), addressed to the one new connection — the same
 * initial-push pattern as {@code InboxPendingSummaryPusher} and
 * {@code ProcessCountsPusher}.
 *
 * <p>Two frames per non-CLOSED process of the session, both straight from
 * the persisted projection:
 *
 * <ul>
 *   <li>{@code process-mode-changed} when the persisted mode is not
 *       NORMAL — Arthur/Eddie Plan-Mode (drives the Web-UI mode badge and
 *       foot's {@code PlanModeState}). {@code oldMode} is reported as
 *       NORMAL: the client missed the real transitions and only reads
 *       {@code newMode} anyway.</li>
 *   <li>{@code todos-updated} when the process has todos — Arthur/Eddie
 *       plan steps, Frankie's and Benjy's TodoList projection.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> an engine callback. The plan is a persisted
 * projection ({@code ThinkProcessDocument.mode/todos}); reading it needs
 * no engine lane, no wake-up and no side effects on BLOCKED/async
 * engines — and the frames must win the race against the first live
 * mutation, which only a synchronous push at bind time can.
 *
 * <p>CLOSED processes are skipped: a stopped process keeps its todos as
 * a record, but pushing them on every reconnect would render a progress
 * box that never changes again.
 *
 * <p>Known residual: the Arthur {@code PLANNING} banner data
 * ({@code plan-proposed}: summary + planVersion) is not persisted, so
 * after a reconnect the Web-UI shows the proposed plan steps but not the
 * "awaiting approval" banner. Foot is unaffected (it renders the steps
 * box, not the banner).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanStateInitialPusher {

    private final ThinkProcessService thinkProcessService;
    private final WebSocketSender sender;

    /**
     * Push the persisted plan state of every open process of the session to
     * the connection that just bound. Call after
     * {@code SessionConnectionRegistry.register}, alongside the other
     * initial pushers and before the handler's reply.
     */
    public void pushInitial(WebSocketSession wsSession, String tenantId, String sessionId) {
        for (ThinkProcessDocument process : thinkProcessService.findBySession(tenantId, sessionId)) {
            if (process.getStatus() == ThinkProcessStatus.CLOSED) {
                continue;
            }
            if (process.getMode() != null && process.getMode() != ProcessMode.NORMAL) {
                send(
                        wsSession,
                        sessionId,
                        MessageType.PROCESS_MODE_CHANGED,
                        ProcessModeChangedNotification.builder()
                                .processId(process.getId() == null ? "" : process.getId())
                                .processName(process.getName())
                                .sessionId(sessionId)
                                .oldMode(ProcessMode.NORMAL)
                                .newMode(process.getMode())
                                .build());
            }
            List<TodoItem> todos = process.getTodos();
            if (todos != null && !todos.isEmpty()) {
                send(
                        wsSession,
                        sessionId,
                        MessageType.TODOS_UPDATED,
                        TodosUpdatedNotification.builder()
                                .processId(process.getId() == null ? "" : process.getId())
                                .processName(process.getName())
                                .sessionId(sessionId)
                                .todos(List.copyOf(todos))
                                .build());
            }
        }
    }

    private void send(WebSocketSession wsSession, String sessionId, String type, Object notification) {
        try {
            sender.sendNotification(wsSession, type, notification);
        } catch (IOException e) {
            log.debug("Failed to push plan state type='{}' to session='{}': {}", type, sessionId, e.toString());
        }
    }
}
