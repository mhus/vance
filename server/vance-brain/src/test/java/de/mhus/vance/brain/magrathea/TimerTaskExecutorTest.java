package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.magrathea.MagratheaWorkflowSource;
import de.mhus.vance.shared.magrathea.MagratheaBoundsSpec;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaTimerDocument;
import de.mhus.vance.shared.magrathea.MagratheaTimerService;
import de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TimerTaskExecutorTest {

    private final MagratheaTimerService timerService = mock(MagratheaTimerService.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final TimerTaskExecutor executor = new TimerTaskExecutor(timerService, taskService);

    @Test
    void happy_path_inserts_timer_links_task_and_returns_async() {
        when(timerService.insert(any())).thenAnswer(inv -> {
            MagratheaTimerDocument t = inv.getArgument(0);
            t.setId("timer-1");
            return t;
        });

        Optional<TaskOutcome> outcome = executor.execute(ctx(timerState("7d")));

        assertThat(outcome).isEmpty();

        ArgumentCaptor<MagratheaTimerDocument> captor = ArgumentCaptor.captor();
        verify(timerService).insert(captor.capture());
        MagratheaTimerDocument inserted = captor.getValue();
        assertThat(inserted.getLinkedTaskId()).isEqualTo("task-1");
        assertThat(inserted.getFiredOutcome()).isEqualTo(TimerTaskExecutor.OUTCOME_FIRED);
        assertThat(inserted.getFireAt()).isAfter(Instant.now().plusSeconds(60 * 60 * 24 * 6));

        verify(taskService).linkTimer("task-1", "timer-1");
    }

    @Test
    void missing_duration_fails() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(timerState(null)));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("'duration:'");
        verify(timerService, never()).insert(any());
    }

    @Test
    void invalid_duration_fails() {
        Optional<TaskOutcome> outcome = executor.execute(ctx(timerState("seven days")));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("ISO-8601");
    }

    @Test
    void timer_insert_failure_fails_synchronously() {
        when(timerService.insert(any())).thenThrow(new RuntimeException("dup"));

        Optional<TaskOutcome> outcome = executor.execute(ctx(timerState("30s")));

        assertThat(outcome.get().outcome()).isEqualTo(TaskCompletedEvent.OUTCOME_FAILURE);
        assertThat(outcome.get().errorMessage()).contains("dup");
        verify(taskService, never()).linkTimer(any(), any());
    }

    private static MagratheaStateSpec timerState(@org.jspecify.annotations.Nullable String duration) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (duration != null) spec.put("duration", duration);
        return new MagratheaStateSpec(
                "wait",
                MagratheaTaskType.TIMER_TASK,
                null, null, null,
                Map.of(),
                Map.of(),
                List.of(),
                MagratheaRetrySpec.none(),
                spec);
    }

    private static MagratheaTaskContext ctx(MagratheaStateSpec state) {
        return new MagratheaTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                new ResolvedMagratheaWorkflow("noop", "", MagratheaWorkflowSource.PROJECT,
                        null, null, null, null, "start",
                        Map.of(), Map.of(), MagratheaBoundsSpec.empty(), List.of(), List.of()),
                state, Map.of(), Map.of());
    }
}
