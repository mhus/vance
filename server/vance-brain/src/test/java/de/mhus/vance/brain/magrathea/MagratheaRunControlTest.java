package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.shared.magrathea.MagratheaJournalEntry;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaTimerService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.JournalRecord;
import de.mhus.vance.shared.magrathea.journal.StatusRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pause / resume / stop on a Magrathea run. The journal and task queue
 * are mocked; what is asserted is the pair of effects each verb has, and
 * the order they happen in — which is where the crash-safety argument
 * lives.
 */
class MagratheaRunControlTest {

    private final MagratheaJournalService journal = mock(MagratheaJournalService.class);
    private final MagratheaTaskService tasks = mock(MagratheaTaskService.class);
    private final MagratheaProjectLaneManager lanes = mock(MagratheaProjectLaneManager.class);
    private final de.mhus.vance.shared.thinkprocess.ThinkProcessService processes =
            mock(de.mhus.vance.shared.thinkprocess.ThinkProcessService.class);
    private final de.mhus.vance.shared.inbox.InboxItemService inbox =
            mock(de.mhus.vance.shared.inbox.InboxItemService.class);
    private final MagratheaTimerService timers = mock(MagratheaTimerService.class);

    private final MagratheaWorkflowService service = new MagratheaWorkflowService(
            mock(MagratheaWorkflowLoader.class),
            mock(de.mhus.vance.shared.document.DocumentService.class),
            journal, tasks, lanes, mock(MagratheaTaskExecutor.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            new de.mhus.vance.shared.metric.MetricService(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            processes, inbox, timers);

    private final List<JournalRecord> appended = new ArrayList<>();

    @BeforeEach
    void wire() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(lanes).submitTracked(any(), any());
        when(journal.append(any(), any(), any(), any(JournalRecord.class))).thenAnswer(inv -> {
            appended.add(inv.getArgument(3));
            return MagratheaJournalEntry.builder().build();
        });
        when(tasks.findByRun(any())).thenReturn(List.of());
    }

    @Test
    void pauseWritesTheStatusBeforeHoldingTheQueue() {
        status(MagratheaRunStatus.RUNNING);

        assertThat(service.pauseRun("acme", "proj", "r1")).isTrue();

        // Order matters: a crash between the two must leave a run that
        // says PAUSED with tasks still queued (they run, and the next
        // enqueue holds), not one that says RUNNING with everything held
        // and nobody left to release it.
        var order = org.mockito.Mockito.inOrder(journal, tasks);
        order.verify(journal).append(any(), any(), any(), any(StatusRecord.class));
        order.verify(tasks).holdRun("r1");
        assertThat(((StatusRecord) appended.get(0)).getStatus()).isEqualTo(MagratheaRunStatus.PAUSED);
    }

    @Test
    void resumeReleasesTheQueueBeforeWritingTheStatus() {
        status(MagratheaRunStatus.PAUSED);

        assertThat(service.resumeRun("acme", "proj", "r1")).isTrue();

        // The reverse order of pause, for the same reason read the other
        // way: a crash here leaves a run that still says PAUSED but makes
        // progress, which the next resume heals.
        var order = org.mockito.Mockito.inOrder(tasks, journal);
        order.verify(tasks).releaseRun("r1");
        order.verify(journal).append(any(), any(), any(), any(StatusRecord.class));
    }

    @Test
    void pausingAPausedRunDoesNothing() {
        status(MagratheaRunStatus.PAUSED);

        assertThat(service.pauseRun("acme", "proj", "r1")).isFalse();
        verify(tasks, never()).holdRun(any());
    }

    @Test
    void stoppingAFinishedRunDoesNothing() {
        status(MagratheaRunStatus.DONE);

        assertThat(service.stopRun("acme", "proj", "r1", "why")).isFalse();
        verify(tasks, never()).holdRun(any());
    }

    @Test
    void stopEndsAGateAndTerminatesRightAway() {
        status(MagratheaRunStatus.RUNNING);
        when(tasks.findByRun("r1")).thenReturn(List.of(
                claimed("t1", MagratheaTaskType.GATE_TASK, t -> t.setInboxItemId("inbox-1"))));

        service.stopRun("acme", "proj", "r1", "why");

        // A gate is deterministically endable — withdraw the item and the
        // run has nothing left to wait for.
        verify(inbox).dismiss(eq("acme"), eq("inbox-1"), any());
        verify(timers).deleteRun("r1");
        assertThat(appended).last().satisfies(r ->
                assertThat(((StatusRecord) r).getStatus()).isEqualTo(MagratheaRunStatus.TERMINATED));
    }

    @Test
    void stopClosesASpawnedAgent() {
        status(MagratheaRunStatus.RUNNING);
        when(tasks.findByRun("r1")).thenReturn(List.of(
                claimed("t1", MagratheaTaskType.AGENT_TASK, t -> t.setSubProcessId("p-1"))));

        service.stopRun("acme", "proj", "r1", "why");

        verify(tasks).unlinkSubProcess("t1");
        verify(processes).closeProcess("p-1", CloseReason.STOPPED);
    }

    @Test
    void stopLeavesTheRunStoppingWhileSomethingOpaqueRuns() {
        status(MagratheaRunStatus.RUNNING);
        // A shell task mid-flight has no handle to pull; the run may not
        // be declared terminal while it is still out there.
        when(tasks.findByRun("r1")).thenReturn(List.of(
                claimed("t1", MagratheaTaskType.SHELL_TASK, t -> { })));

        service.stopRun("acme", "proj", "r1", "why");

        assertThat(appended).noneSatisfy(r ->
                assertThat(((StatusRecord) r).getStatus()).isEqualTo(MagratheaRunStatus.TERMINATED));
    }

    @Test
    void stopCascadesIntoASubRun() {
        status(MagratheaRunStatus.RUNNING);
        when(tasks.findByRun("r1")).thenReturn(List.of(
                claimed("t1", MagratheaTaskType.WORKFLOW_TASK, t -> t.setSubWorkflowRunId("r2"))));
        when(tasks.findByRun("r2")).thenReturn(List.of());

        service.stopRun("acme", "proj", "r1", "why");

        // The child run is stopped through the same path, so its own
        // in-flight work is unwound too.
        verify(tasks).holdRun("r2");
    }

    private void status(MagratheaRunStatus status) {
        when(journal.readLast(any(), any(), eq("r1"), eq(StatusRecord.class)))
                .thenReturn(status == MagratheaRunStatus.RUNNING
                        ? Optional.empty()
                        : Optional.of(StatusRecord.builder().status(status).build()));
        when(journal.readLast(any(), any(), eq("r2"), eq(StatusRecord.class)))
                .thenReturn(Optional.empty());
    }

    private static MagratheaTaskDocument claimed(
            String id, MagratheaTaskType type, java.util.function.Consumer<MagratheaTaskDocument> tweak) {
        MagratheaTaskDocument t = MagratheaTaskDocument.builder()
                .id(id).tenantId("acme").projectId("proj").workflowRunId("r1")
                .taskType(type).status(MagratheaTaskStatus.CLAIMED).build();
        tweak.accept(t);
        return t;
    }
}
