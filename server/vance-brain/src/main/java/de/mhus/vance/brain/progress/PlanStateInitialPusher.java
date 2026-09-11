package de.mhus.vance.brain.progress;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.ProcessModeChangedNotification;
import de.mhus.vance.api.thinkprocess.ProcessPlanState;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Restores the plan/todo UI state on a freshly bound connection: a
 * reconnecting client must see the current plan without waiting for the
 * next engine mutation. Once per session-bind (create / resume /
 * bootstrap), straight from the persisted projection
 * ({@code ThinkProcessDocument.mode/todos}) — deliberately not an engine
 * callback: reading it needs no engine lane, no wake-up and no side
 * effects on BLOCKED/async engines.
 *
 * <p>Two halves, two carriers:
 *
 * <ul>
 *   <li>{@link #collectPlanStates} — the <b>reply</b> half. The resume /
 *       bootstrap handlers put the list into the response's
 *       {@code planStates} field, next to {@code activeProcesses}: a
 *       freshly bound client cannot correlate the pushed frames — the
 *       Web-UI learns its chat-process pointer only after the bind
 *       completes, so {@code todos-updated} frames sent during the
 *       handler land before the filter that would accept them. The reply
 *       carries both in one round-trip (§5.2b, same ordering rationale
 *       as the busy-restore §5.2a).</li>
 *   <li>{@link #pushInitial} — the <b>frame</b> half, for clients that
 *       correlate by process name alone (foot's scrollback box renders
 *       every {@code todos-updated} it sees). Web-UI clients drop these
 *       frames; they restore from the reply instead.</li>
 * </ul>
 *
 * <p>Per non-CLOSED process with something to show:
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
 * <p>CLOSED processes are skipped: a stopped process keeps its todos as
 * a record, but restoring them on every reconnect would render a progress
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
     * Collects the persisted plan state of every open process of the session
     * — the reply-carrier half of the restore: the resume / bootstrap
     * handlers put it into {@code planStates} so a freshly bound client
     * restores its plan/todo UI in the same round-trip that also carries its
     * chat-process pointer (a client cannot correlate the pushed frames
     * until the bind completes — see §5.2b).
     */
    public List<ProcessPlanState> collectPlanStates(String tenantId, String sessionId) {
        List<ProcessPlanState> out = new ArrayList<>();
        for (ThinkProcessDocument process : thinkProcessService.findBySession(tenantId, sessionId)) {
            if (process.getStatus() == ThinkProcessStatus.CLOSED) {
                continue;
            }
            List<TodoItem> todos = process.getTodos() == null ? List.of() : process.getTodos();
            ProcessMode mode = process.getMode() == null ? ProcessMode.NORMAL : process.getMode();
            if (mode == ProcessMode.NORMAL && todos.isEmpty()) {
                continue;
            }
            out.add(ProcessPlanState.builder()
                    .processId(process.getId() == null ? "" : process.getId())
                    .processName(process.getName())
                    .mode(mode)
                    .todos(todos)
                    .build());
        }
        return out;
    }

    /**
     * Pushes the collected plan state as frames to the connection that just
     * bound — the frame half of the restore, for clients that correlate by
     * process name alone (foot's scrollback box). Call after
     * {@code SessionConnectionRegistry.register}, alongside the other
     * initial pushers and before the handler's reply.
     */
    public void pushInitial(WebSocketSession wsSession, String tenantId, String sessionId) {
        for (ProcessPlanState plan : collectPlanStates(tenantId, sessionId)) {
            if (plan.getMode() != ProcessMode.NORMAL) {
                send(
                        wsSession,
                        sessionId,
                        MessageType.PROCESS_MODE_CHANGED,
                        ProcessModeChangedNotification.builder()
                                .processId(plan.getProcessId())
                                .processName(plan.getProcessName())
                                .sessionId(sessionId)
                                .oldMode(ProcessMode.NORMAL)
                                .newMode(plan.getMode())
                                .build());
            }
            if (!plan.getTodos().isEmpty()) {
                send(
                        wsSession,
                        sessionId,
                        MessageType.TODOS_UPDATED,
                        TodosUpdatedNotification.builder()
                                .processId(plan.getProcessId())
                                .processName(plan.getProcessName())
                                .sessionId(sessionId)
                                .todos(List.copyOf(plan.getTodos()))
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
