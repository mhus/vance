package de.mhus.vance.brain.magrathea;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class MagratheaWatchdogScannerTest {

    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaWorkflowService workflowService = mock(MagratheaWorkflowService.class);
    private final MagratheaProperties properties = new MagratheaProperties();
    private final MagratheaJournalService journalService = mock(MagratheaJournalService.class);
    private final MagratheaWorkflowLoader workflowLoader = mock(MagratheaWorkflowLoader.class);
    private final ClusterMasterService masterService = mock(ClusterMasterService.class);
    private final MagratheaWatchdogScanner scanner = new MagratheaWatchdogScanner(
            taskService, workflowService, properties,
            journalService, workflowLoader, provider(masterService));

    {
        when(masterService.isLocalPodMaster()).thenReturn(true);
    }

    @Test
    void aStalledTask_failsItsRun() {
        when(taskService.findStalledBefore(any(), anyInt())).thenReturn(List.of(task("run-1", "work")));

        scanner.scan();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(workflowService).failStalledRun(eq("acme"), eq("proj"), eq("run-1"), reason.capture());
        // The reason has to name what was stuck — the run view shows it and
        // it is the only clue left about a defect nobody witnessed.
        org.assertj.core.api.Assertions.assertThat(reason.getValue())
                .contains("watchdog").contains("work");
    }

    @Test
    void severalStalledTasksOfOneRun_failItOnce() {
        when(taskService.findStalledBefore(any(), anyInt()))
                .thenReturn(List.of(task("run-1", "a"), task("run-1", "b")));

        scanner.scan();

        verify(workflowService, times(1)).failStalledRun(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aZeroCeiling_disablesTheScan() {
        properties.setStallCeiling(Duration.ZERO);

        scanner.scan();

        verifyNoInteractions(taskService);
    }

    @Test
    void aWedgedRun_doesNotStopTheScan() {
        // A jammed lane is one of the things the watchdog is for, so a
        // throwing run must not take the remaining ones down with it.
        when(taskService.findStalledBefore(any(), anyInt()))
                .thenReturn(List.of(task("run-1", "a"), task("run-2", "b")));
        when(workflowService.failStalledRun(anyString(), anyString(), eq("run-1"), anyString()))
                .thenThrow(new MagratheaWorkflowService.MagratheaWorkflowException("lane wedged"));

        scanner.scan();

        verify(workflowService).failStalledRun(anyString(), anyString(), eq("run-2"), anyString());
    }

    @Test
    void nothingStalled_touchesNoRun() {
        when(taskService.findStalledBefore(any(), anyInt())).thenReturn(List.of());

        scanner.scan();

        verify(workflowService, never()).failStalledRun(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aNonMasterPod_doesNotSweep() {
        // The unwind is a chain of side effects with no compare-and-set in
        // it — two pods would end the same run twice.
        when(masterService.isLocalPodMaster()).thenReturn(false);

        scanner.scan();

        verifyNoInteractions(taskService);
    }

    @Test
    void withoutAMasterService_theLonePodSweeps() {
        // Cluster-master switched off means single-pod; refusing there
        // would disable the net where nobody can take over.
        MagratheaWatchdogScanner lone = new MagratheaWatchdogScanner(
                taskService, workflowService, properties,
                journalService, workflowLoader, provider(null));
        when(taskService.findStalledBefore(any(), anyInt())).thenReturn(List.of(task("run-1", "work")));

        lone.scan();

        verify(workflowService).failStalledRun(anyString(), anyString(), eq("run-1"), anyString());
    }

    // ── The one declared opt-out ───────────────────────────────────

    @Test
    void aStateDeclaringTimeoutZero_isLeftStanding() {
        // `timeoutSeconds: 0` is the only way an author says "this one really
        // may wait forever" (workflows.md §12a.1). Without this exemption the
        // two nets contradicted each other and the watchdog won: a gate left
        // open on purpose was failed after the ceiling with the reason "no
        // progress" — reported as a defect when it was the written intent.
        givenFrozenPlan("""
                start: ask
                states:
                  ask:
                    type: gate_task
                    timeoutSeconds: 0
                    inbox: { kind: APPROVAL, title: "ok?" }
                    on: { approved: done }
                  done:
                    type: terminal
                    outcome: success
                """);
        when(taskService.findStalledBefore(any(), anyInt()))
                .thenReturn(List.of(task("run-1", "ask")));

        scanner.scan();

        verify(workflowService, never())
                .failStalledRun(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aStateWithADeclaredDeadline_isStillFailed() {
        givenFrozenPlan("""
                start: ask
                states:
                  ask:
                    type: gate_task
                    timeoutSeconds: 60
                    inbox: { kind: APPROVAL, title: "ok?" }
                    on: { approved: done }
                  done:
                    type: terminal
                    outcome: success
                """);
        when(taskService.findStalledBefore(any(), anyInt()))
                .thenReturn(List.of(task("run-1", "ask")));

        scanner.scan();

        verify(workflowService).failStalledRun(anyString(), anyString(), eq("run-1"), anyString());
    }

    @Test
    void anUnreadableFrozenPlan_isNotTreatedAsAnOptOut() {
        // A run whose definition cannot be parsed is exactly the kind of
        // defect this net exists for; inventing a waiver out of a failure
        // would make the last net vanish where it is needed most.
        when(journalService.readLast(any(), any(), any(), eq(StartRecord.class)))
                .thenReturn(java.util.Optional.of(StartRecord.builder()
                        .workflowName("demo").definitionYaml("not: [a plan").build()));
        when(workflowLoader.validateYaml(any(), any()))
                .thenThrow(new IllegalStateException("unparseable"));
        when(taskService.findStalledBefore(any(), anyInt()))
                .thenReturn(List.of(task("run-1", "ask")));

        scanner.scan();

        verify(workflowService).failStalledRun(anyString(), anyString(), eq("run-1"), anyString());
    }

    private void givenFrozenPlan(String yaml) {
        when(journalService.readLast(any(), any(), any(), eq(StartRecord.class)))
                .thenReturn(java.util.Optional.of(StartRecord.builder()
                        .workflowName("demo").definitionYaml(yaml).build()));
        when(workflowLoader.validateYaml(any(), any()))
                .thenReturn(MagratheaWorkflowLoader.parseYaml("demo", yaml));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ClusterMasterService> provider(
            @org.jspecify.annotations.Nullable ClusterMasterService service) {
        ObjectProvider<ClusterMasterService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    private static MagratheaTaskDocument task(String runId, String stateName) {
        return MagratheaTaskDocument.builder()
                .id("task-" + stateName)
                .tenantId("acme")
                .projectId("proj")
                .workflowRunId(runId)
                .stateName(stateName)
                .status(MagratheaTaskStatus.CLAIMED)
                .createdAt(Instant.now().minus(Duration.ofDays(30)))
                .build();
    }
}
