package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaRetrySpec;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTimerDocument;
import de.mhus.vance.shared.magrathea.MagratheaTimerService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MagratheaTimeoutSchedulerTest {

    private final MagratheaTimerService timerService = mock(MagratheaTimerService.class);
    private final MagratheaProperties properties = new MagratheaProperties();
    private final MagratheaTimeoutScheduler scheduler =
            new MagratheaTimeoutScheduler(timerService, properties);

    @Test
    void armsATimerCarryingTheTimeoutOutcome() {
        scheduler.arm(ctx(), state(600));

        var captor = org.mockito.ArgumentCaptor.forClass(MagratheaTimerDocument.class);
        verify(timerService).insert(captor.capture());
        MagratheaTimerDocument timer = captor.getValue();
        assertThat(timer.getLinkedTaskId()).isEqualTo("task-1");
        assertThat(timer.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(timer.getFiredOutcome()).isEqualTo(MagratheaTimeoutScheduler.OUTCOME_TIMEOUT);
        assertThat(timer.getFireAt()).isAfter(Instant.now().plusSeconds(590));
    }

    @Test
    void withoutADeclaredTimeout_armsTheTypeDefault() {
        // The point of the whole net: an author who did not anticipate the
        // defect also did not write a deadline for it.
        scheduler.arm(ctx(), state(null));

        var captor = org.mockito.ArgumentCaptor.forClass(MagratheaTimerDocument.class);
        verify(timerService).insert(captor.capture());
        assertThat(captor.getValue().getFireAt())
                .isAfter(Instant.now().plus(properties.getDefaultAgentTimeout().minusMinutes(1)));
    }

    @Test
    void aDeclaredZero_armsNothing() {
        // Explicit opt-out: "this one really may wait forever".
        scheduler.arm(ctx(), state(0));

        verify(timerService, never()).insert(any());
    }

    @Test
    void perTypeDefaults_differ() {
        assertThat(scheduler.effectiveTimeout(state(null, MagratheaTaskType.GATE_TASK)))
                .isEqualTo(properties.getDefaultGateTimeout());
        assertThat(scheduler.effectiveTimeout(state(null, MagratheaTaskType.WORKFLOW_TASK)))
                .isEqualTo(properties.getDefaultSubWorkflowTimeout());
    }

    @Test
    void aTypeWithoutADefault_getsNoDeadline() {
        // Only the three async types are armed at all; a synchronous type
        // blocks its executor and is bounded there.
        assertThat(scheduler.effectiveTimeout(state(null, MagratheaTaskType.SHELL_TASK))).isNull();
    }

    @Test
    void aFailedInsertDoesNotFailTheTask() {
        // Losing the deadline degrades the task to what it was before
        // deadlines existed; failing it would discard work already running.
        doThrow(new IllegalStateException("mongo down")).when(timerService).insert(any());

        assertThatCode(() -> scheduler.arm(ctx(), state(60))).doesNotThrowAnyException();
    }

    private static MagratheaStateSpec state(Integer timeoutSeconds) {
        return state(timeoutSeconds, MagratheaTaskType.AGENT_TASK);
    }

    private static MagratheaStateSpec state(Integer timeoutSeconds, MagratheaTaskType type) {
        return new MagratheaStateSpec(
                "work", type, null, timeoutSeconds, null,
                Map.of(), Map.of(), List.of(), MagratheaRetrySpec.none(), Map.of());
    }

    private static MagratheaTaskContext ctx() {
        return new MagratheaTaskContext(
                "acme", "proj", "run-1", "task-1", "alice",
                new de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow(
                        "noop", "", de.mhus.vance.api.magrathea.MagratheaWorkflowSource.PROJECT,
                        null, null, null, null, "work",
                        Map.of(), Map.of(),
                        de.mhus.vance.shared.magrathea.MagratheaBoundsSpec.empty(),
                        List.of(), List.of()),
                state(600), Map.of(), Map.of());
    }
}
