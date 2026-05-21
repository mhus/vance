package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaTimerDocument;
import de.mhus.vance.shared.magrathea.MagratheaTimerService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MagratheaTimerScannerTest {

    private final MagratheaTimerService timerService = mock(MagratheaTimerService.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaCompletionEventBus eventBus = mock(MagratheaCompletionEventBus.class);
    private final MagratheaTimerScanner scanner = new MagratheaTimerScanner(
            timerService, taskService, eventBus);

    @Test
    void no_due_timers_publishes_nothing() {
        when(timerService.findPending(any(), anyInt())).thenReturn(List.of());

        scanner.scan();

        verify(eventBus, never()).publish(any());
    }

    @Test
    void due_timer_publishes_TaskCompletedEvent_for_linked_task() {
        MagratheaTimerDocument timer = timer("t-1", "task-1", "fired");
        when(timerService.findPending(any(), anyInt())).thenReturn(List.of(timer));
        when(timerService.claimFire(eq("t-1"), any())).thenReturn(Optional.of(timer));
        when(taskService.findById("task-1")).thenReturn(Optional.of(task("task-1", "wait")));

        scanner.scan();

        ArgumentCaptor<TaskCompletedEvent> captor = ArgumentCaptor.captor();
        verify(eventBus).publish(captor.capture());
        TaskCompletedEvent ev = captor.getValue();
        assertThat(ev.taskId()).isEqualTo("task-1");
        assertThat(ev.stateName()).isEqualTo("wait");
        assertThat(ev.outcome()).isEqualTo("fired");
    }

    @Test
    void lost_claim_race_does_not_publish() {
        MagratheaTimerDocument timer = timer("t-2", "task-2", "fired");
        when(timerService.findPending(any(), anyInt())).thenReturn(List.of(timer));
        when(timerService.claimFire(eq("t-2"), any())).thenReturn(Optional.empty());

        scanner.scan();

        verify(eventBus, never()).publish(any());
        verify(taskService, never()).findById(any());
    }

    @Test
    void orphan_timer_with_gone_task_is_skipped() {
        MagratheaTimerDocument timer = timer("t-3", "gone-task", "fired");
        when(timerService.findPending(any(), anyInt())).thenReturn(List.of(timer));
        when(timerService.claimFire(eq("t-3"), any())).thenReturn(Optional.of(timer));
        when(taskService.findById("gone-task")).thenReturn(Optional.empty());

        scanner.scan();

        verify(eventBus, never()).publish(any());
    }

    @Test
    void gate_timeout_outcome_is_forwarded_verbatim() {
        MagratheaTimerDocument timer = timer("t-gate", "task-gate", "timeout");
        when(timerService.findPending(any(), anyInt())).thenReturn(List.of(timer));
        when(timerService.claimFire(eq("t-gate"), any())).thenReturn(Optional.of(timer));
        when(taskService.findById("task-gate")).thenReturn(Optional.of(task("task-gate", "review")));

        scanner.scan();

        ArgumentCaptor<TaskCompletedEvent> captor = ArgumentCaptor.captor();
        verify(eventBus).publish(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo("timeout");
    }

    private static MagratheaTimerDocument timer(String id, String linkedTaskId, String outcome) {
        return MagratheaTimerDocument.builder()
                .id(id)
                .tenantId("acme").projectId("proj").workflowRunId("r1")
                .linkedTaskId(linkedTaskId)
                .firedOutcome(outcome)
                .fireAt(Instant.now())
                .build();
    }

    private static MagratheaTaskDocument task(String id, String stateName) {
        return MagratheaTaskDocument.builder()
                .id(id)
                .tenantId("acme").projectId("proj").workflowRunId("r1")
                .workflowName("demo").stateName(stateName)
                .taskType(MagratheaTaskType.TIMER_TASK)
                .build();
    }
}
