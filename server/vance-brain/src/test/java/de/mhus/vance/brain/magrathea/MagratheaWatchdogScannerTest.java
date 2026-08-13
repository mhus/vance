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
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MagratheaWatchdogScannerTest {

    private final MagratheaTaskService taskService = mock(MagratheaTaskService.class);
    private final MagratheaWorkflowService workflowService = mock(MagratheaWorkflowService.class);
    private final MagratheaProperties properties = new MagratheaProperties();
    private final MagratheaWatchdogScanner scanner =
            new MagratheaWatchdogScanner(taskService, workflowService, properties);

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
