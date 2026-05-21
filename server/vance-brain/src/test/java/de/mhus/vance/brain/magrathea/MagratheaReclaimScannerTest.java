package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class MagratheaReclaimScannerTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaCompletionEventBus eventBus = mock(MagratheaCompletionEventBus.class);
    private final MagratheaReclaimScanner scanner = new MagratheaReclaimScanner(
            mongoTemplate, taskService, eventBus);

    @Test
    void no_stale_tasks_does_nothing() {
        when(mongoTemplate.find(any(Query.class), eq(MagratheaTaskDocument.class)))
                .thenReturn(List.of());

        scanner.scan();

        verify(taskService, never()).reclaim(any());
        verify(eventBus, never()).publish(any());
    }

    @Test
    void stale_task_under_attempt_limit_is_reclaimed_to_PENDING() {
        MagratheaTaskDocument task = task("t-1", 1);
        when(mongoTemplate.find(any(Query.class), eq(MagratheaTaskDocument.class)))
                .thenReturn(List.of(task));
        when(taskService.reclaim(eq("t-1"))).thenReturn(Optional.of(task));

        scanner.scan();

        verify(taskService).reclaim("t-1");
        verify(eventBus, never()).publish(any());
    }

    @Test
    void exhausted_attempts_publishes_technical_error_event() {
        MagratheaTaskDocument task = task("t-exhausted", 3);
        when(mongoTemplate.find(any(Query.class), eq(MagratheaTaskDocument.class)))
                .thenReturn(List.of(task));

        scanner.scan();

        ArgumentCaptor<TaskCompletedEvent> captor = ArgumentCaptor.captor();
        verify(eventBus).publish(captor.capture());
        TaskCompletedEvent ev = captor.getValue();
        assertThat(ev.outcome()).isEqualTo("technical_error");
        assertThat(ev.taskId()).isEqualTo("t-exhausted");
        assertThat(ev.errorMessage()).contains("max claim attempts");

        verify(taskService, never()).reclaim(any());
    }

    @Test
    void reclaim_race_lost_is_silent() {
        MagratheaTaskDocument task = task("t-race", 1);
        when(mongoTemplate.find(any(Query.class), eq(MagratheaTaskDocument.class)))
                .thenReturn(List.of(task));
        when(taskService.reclaim(eq("t-race"))).thenReturn(Optional.empty());

        scanner.scan();

        verify(eventBus, never()).publish(any());
    }

    @Test
    void mixed_batch_reclaims_some_and_fails_others() {
        MagratheaTaskDocument fresh = task("t-fresh", 0);
        MagratheaTaskDocument retried = task("t-retried", 2);
        MagratheaTaskDocument exhausted = task("t-exhausted", 3);
        when(mongoTemplate.find(any(Query.class), eq(MagratheaTaskDocument.class)))
                .thenReturn(List.of(fresh, retried, exhausted));
        when(taskService.reclaim(any())).thenReturn(Optional.of(fresh));

        scanner.scan();

        verify(taskService).reclaim("t-fresh");
        verify(taskService).reclaim("t-retried");
        verify(taskService, never()).reclaim("t-exhausted");
        verify(eventBus).publish(any());
    }

    private static MagratheaTaskDocument task(String id, int attemptCount) {
        return MagratheaTaskDocument.builder()
                .id(id)
                .tenantId("acme").projectId("proj").workflowRunId("r1")
                .workflowName("demo").stateName("plan")
                .taskType(MagratheaTaskType.SHELL_TASK)
                .status(MagratheaTaskStatus.CLAIMED)
                .claimedBy("dead-pod")
                .claimedAt(Instant.now().minusSeconds(600))
                .attemptCount(attemptCount)
                .build();
    }
}
