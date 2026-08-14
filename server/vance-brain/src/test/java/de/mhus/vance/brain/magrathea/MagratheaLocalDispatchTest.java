package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MagratheaLocalDispatchTest {

    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager laneManager = mock(MagratheaProjectLaneManager.class);
    private final MagratheaTaskExecutor taskExecutor = mock(MagratheaTaskExecutor.class);
    private final MagratheaPodIdentity podIdentity = new MagratheaPodIdentity();

    private final MagratheaLocalDispatch dispatch =
            new MagratheaLocalDispatch(taskService, laneManager, taskExecutor, podIdentity);

    private static MagratheaTaskDocument task(MagratheaTaskStatus status, Instant nextAttemptAt) {
        return MagratheaTaskDocument.builder()
                .id("task-1")
                .tenantId("t")
                .projectId("p")
                .workflowRunId("run-1")
                .workflowName("wf")
                .stateName("s1")
                .taskType(MagratheaTaskType.CONDITION_TASK)
                .status(status)
                .createdAt(Instant.now())
                .nextAttemptAt(nextAttemptAt)
                .build();
    }

    @Test
    void dispatch_pendingAndDue_claimsAndSubmitsToLane() {
        MagratheaTaskDocument pending = task(MagratheaTaskStatus.PENDING, Instant.now());
        when(taskService.claim(eq("task-1"), anyString(), any()))
                .thenReturn(Optional.of(pending));

        dispatch.dispatch(pending);

        verify(taskService).claim(eq("task-1"), anyString(), any());
        verify(laneManager).submit(eq("p"), any(Runnable.class));
    }

    @Test
    void dispatch_heldTask_isLeftAlone() {
        // A HELD row belongs to a paused run — the hold is the point.
        dispatch.dispatch(task(MagratheaTaskStatus.HELD, Instant.now()));

        verify(taskService, never()).claim(anyString(), anyString(), any());
        verify(laneManager, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void dispatch_retryStillInBackoff_isLeftToTheClaimer() {
        // Running it now would defeat the back-off the retry spec asked for.
        dispatch.dispatch(task(MagratheaTaskStatus.PENDING, Instant.now().plusSeconds(60)));

        verify(taskService, never()).claim(anyString(), anyString(), any());
        verify(laneManager, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void dispatch_claimLostToAnotherPod_doesNotSubmit() {
        MagratheaTaskDocument pending = task(MagratheaTaskStatus.PENDING, Instant.now());
        when(taskService.claim(eq("task-1"), anyString(), any())).thenReturn(Optional.empty());

        dispatch.dispatch(pending);

        verify(laneManager, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void dispatch_claimThrows_doesNotPropagate() {
        // The fast path must never break the completion that triggered it:
        // the row stays PENDING and the claim scanner is the safety net.
        MagratheaTaskDocument pending = task(MagratheaTaskStatus.PENDING, Instant.now());
        when(taskService.claim(eq("task-1"), anyString(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        assertThatCode(() -> dispatch.dispatch(pending)).doesNotThrowAnyException();

        verify(laneManager, never()).submit(anyString(), any(Runnable.class));
    }

    @Test
    void dispatch_nullTask_isIgnored() {
        assertThatCode(() -> dispatch.dispatch(null)).doesNotThrowAnyException();

        verify(taskService, never()).claim(anyString(), anyString(), any());
    }
}
