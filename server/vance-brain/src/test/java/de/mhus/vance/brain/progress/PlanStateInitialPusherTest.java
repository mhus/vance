package de.mhus.vance.brain.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.ProcessModeChangedNotification;
import de.mhus.vance.api.thinkprocess.ProcessPlanState;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.api.thinkprocess.TodosUpdatedNotification;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

/**
 * Pins the reconnect-restore contract of {@link PlanStateInitialPusher}:
 * the freshly bound connection must receive the persisted plan state —
 * {@code todos-updated} for every open process with todos (Frankie/Benjy
 * TodoList, Arthur/Eddie plan steps), {@code process-mode-changed} for
 * processes in a Plan-Mode — and nothing for closed or plan-less
 * processes (no stale boxes, no pointless frames).
 */
class PlanStateInitialPusherTest {

    private static final String TENANT = "t-1";
    private static final String SESSION = "sess-1";

    private ThinkProcessService thinkProcessService;
    private WebSocketSender sender;
    private WebSocketSession wsSession;
    private PlanStateInitialPusher pusher;

    @BeforeEach
    void setUp() {
        thinkProcessService = mock(ThinkProcessService.class);
        sender = mock(WebSocketSender.class);
        wsSession = mock(WebSocketSession.class);
        pusher = new PlanStateInitialPusher(thinkProcessService, sender);
    }

    private static ThinkProcessDocument process(String name, ThinkProcessStatus status) {
        ThinkProcessDocument doc = new ThinkProcessDocument();
        doc.setId("proc-" + name);
        doc.setName(name);
        doc.setSessionId(SESSION);
        doc.setStatus(status);
        return doc;
    }

    @Test
    void pushesTodosForOpenProcess_withNormalModeNoModeFrame() throws Exception {
        ThinkProcessDocument benjy = process("benjy", ThinkProcessStatus.RUNNING);
        benjy.setTodos(List.of(TodoItem.builder()
                .id("1")
                .content("item one")
                .status(TodoStatus.IN_PROGRESS)
                .build()));
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of(benjy));

        pusher.pushInitial(wsSession, TENANT, SESSION);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendNotification(eq(wsSession), eq(MessageType.TODOS_UPDATED), payload.capture());
        TodosUpdatedNotification frame = (TodosUpdatedNotification) payload.getValue();
        assertThat(frame.getProcessName()).isEqualTo("benjy");
        assertThat(frame.getTodos()).hasSize(1);
        assertThat(frame.getTodos().getFirst().getContent()).isEqualTo("item one");
        // NORMAL mode must not produce a mode frame — only the todos frame.
        verify(sender, never()).sendNotification(any(), eq(MessageType.PROCESS_MODE_CHANGED), any());
    }

    @Test
    void pushesModeFrameForPlanModeProcess() throws Exception {
        ThinkProcessDocument arthur = process("chat", ThinkProcessStatus.IDLE);
        arthur.setMode(ProcessMode.EXECUTING);
        arthur.setTodos(List.of(TodoItem.builder()
                .id("1")
                .content("plan step")
                .status(TodoStatus.PENDING)
                .build()));
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of(arthur));

        pusher.pushInitial(wsSession, TENANT, SESSION);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendNotification(eq(wsSession), eq(MessageType.PROCESS_MODE_CHANGED), payload.capture());
        // The client missed the real transitions — restore reports the
        // persisted mode as the new mode.
        ProcessModeChangedNotification frame = (ProcessModeChangedNotification) payload.getValue();
        assertThat(frame.getNewMode()).isEqualTo(ProcessMode.EXECUTING);
        assertThat(frame.getOldMode()).isEqualTo(ProcessMode.NORMAL);
        verify(sender).sendNotification(any(), eq(MessageType.TODOS_UPDATED), any(TodosUpdatedNotification.class));
    }

    @Test
    void skipsClosedProcessesAndPlanlessOnes() throws Exception {
        ThinkProcessDocument closed = process("frankie-stopped", ThinkProcessStatus.CLOSED);
        closed.setTodos(List.of(TodoItem.builder().id("1").content("leftover").build()));
        ThinkProcessDocument idleChat = process("chat", ThinkProcessStatus.IDLE);
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of(closed, idleChat));

        pusher.pushInitial(wsSession, TENANT, SESSION);

        verify(sender, never()).sendNotification(any(), any(), any());
    }

    @Test
    void sendFailureIsSwallowed_oneBrokenFrameDoesNotKillTheRest() throws Exception {
        ThinkProcessDocument benjy = process("benjy", ThinkProcessStatus.BLOCKED);
        benjy.setTodos(List.of(TodoItem.builder().id("1").content("item").build()));
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of(benjy));
        // void stubbing needs doThrow — when() is not allowed on void methods.
        org.mockito.Mockito.doThrow(new java.io.IOException("socket gone"))
                .when(sender)
                .sendNotification(any(), any(), any());

        pusher.pushInitial(wsSession, TENANT, SESSION);

        verify(sender).sendNotification(any(), any(), any());
    }

    @Test
    void collectReturnsOnlyProcessesWithSomethingToShow() {
        // CLOSED with todos — historical record, not a live plan.
        ThinkProcessDocument closed = process("frankie-stopped", ThinkProcessStatus.CLOSED);
        closed.setTodos(List.of(TodoItem.builder().id("1").content("leftover").build()));
        // IDLE, NORMAL, no todos — nothing to restore.
        ThinkProcessDocument plain = process("worker-1", ThinkProcessStatus.IDLE);
        // Frankie/Benjy shape: NORMAL mode, todos present.
        ThinkProcessDocument frankie = process("chat", ThinkProcessStatus.RUNNING);
        frankie.setTodos(List.of(TodoItem.builder().id("1").content("item one").build()));
        // Arthur shape: EXECUTING mode, todos empty.
        ThinkProcessDocument arthur = process("arthur", ThinkProcessStatus.BLOCKED);
        arthur.setMode(ProcessMode.EXECUTING);
        when(thinkProcessService.findBySession(TENANT, SESSION)).thenReturn(List.of(closed, plain, frankie, arthur));

        java.util.List<ProcessPlanState> states = pusher.collectPlanStates(TENANT, SESSION);

        org.assertj.core.api.Assertions.assertThat(states)
                .extracting(ProcessPlanState::getProcessName)
                .containsExactly("chat", "arthur");
        org.assertj.core.api.Assertions.assertThat(states.getFirst().getMode()).isEqualTo(ProcessMode.NORMAL);
        org.assertj.core.api.Assertions.assertThat(states.getFirst().getTodos()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(states.get(1).getMode()).isEqualTo(ProcessMode.EXECUTING);
    }
}
