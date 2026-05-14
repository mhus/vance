package de.mhus.vance.brain.hactar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.hactar.HactarTaskType;
import de.mhus.vance.api.hactar.HactarWorkflowSource;
import de.mhus.vance.shared.hactar.HactarBoundsSpec;
import de.mhus.vance.shared.hactar.HactarRetrySpec;
import de.mhus.vance.shared.hactar.HactarStateSpec;
import de.mhus.vance.shared.hactar.HactarTaskService;
import de.mhus.vance.shared.hactar.HactarTimerDocument;
import de.mhus.vance.shared.hactar.HactarTimerService;
import de.mhus.vance.shared.hactar.ResolvedHactarWorkflow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TimerTaskExecutorTest {

    private final HactarTimerService timerService = mock(HactarTimerService.class);
    private final HactarTaskService taskService = mock(HactarTaskService.class);
    private final TimerTaskExecutor executor = new TimerTaskExecutor(timerService, taskService);

    @Test
    void happy_path_inserts_timer_links_task_and_returns_async() {
        when(timerService.insert(any())).thenAnswer(inv -> {
            HactarTimerDocument t = inv.getArgument(0);
            t.setId("timer-1");
            return t;
        });

        Optional<TaskOutcome> outcome = executor.execute(ctx(timerState("7d")));

        assertThat(outcome).isEmpty();

        ArgumentCaptor<HactarTimerDocument> captor = ArgumentCaptor.captor();
        verify(timerService).insert(captor.capture());
        HactarTimerDocument inserted = captor.getValue();
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

    private static HactarStateSpec timerState(@org.jspecify.annotations.Nullable String duration) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (duration != null) spec.put("duration", duration);
        return new HactarStateSpec(
                "wait",
                HactarTaskType.TIMER_TASK,
                null, null, null,
                Map.of(),
                Map.of(),
                List.of(),
                HactarRetrySpec.none(),
                spec);
    }

    private static HactarTaskContext ctx(HactarStateSpec state) {
        return new HactarTaskContext(
                "acme", "proj", "r1", "task-1", "alice",
                new ResolvedHactarWorkflow("noop", "", HactarWorkflowSource.PROJECT,
                        null, null, null, null, "start",
                        Map.of(), Map.of(), HactarBoundsSpec.empty(), List.of(), List.of()),
                state, Map.of(), Map.of());
    }
}
