package de.mhus.vance.brain.ursascheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.action.TriggerKind;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import de.mhus.vance.shared.thinkprocess.TriggerOrigin;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Run attribution now comes off the process's {@link TriggerOrigin} instead
 * of a {@code STARTED} lookup in the event log — see
 * {@code planning/megadodo.md}.
 */
@ExtendWith(MockitoExtension.class)
class UrsaSchedulerProcessTerminationListenerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj";
    private static final String PROCESS = "p-1";
    private static final String SOURCE = "ursascheduler:nightly";
    private static final String RUN = "run_7";

    @Mock
    private ThinkProcessService thinkProcessService;
    @Mock
    private UrsaSchedulerService schedulerService;
    @Mock
    private SchedulerLogService schedulerLogService;
    @Mock
    private de.mhus.vance.shared.megadodo.MegadodoService megadodoService;

    @InjectMocks
    private UrsaSchedulerProcessTerminationListener listener;

    @Test
    void closedSchedulerProcess_writesTerminalRowAndClosesRunLog() {
        givenProcess(origin(TriggerKind.SCHEDULER, SOURCE, RUN), CloseReason.DONE);

        listener.onStatusChanged(closedEvent());

        verify(schedulerLogService).onTerminated(eq(RUN), eq("completed"), any());
        verify(megadodoService).schedulerRunFinished(
                eq(TENANT), eq(PROJECT), eq("nightly"), eq(RUN), eq(true), any(), any());
        verify(schedulerService).onProcessTerminated(TENANT, PROJECT, PROCESS);
    }

    @Test
    void blockedSchedulerProcess_onlyMarksTheRunLogPending() {
        givenProcess(origin(TriggerKind.SCHEDULER, SOURCE, RUN), null);

        listener.onStatusChanged(new ThinkProcessStatusChangedEvent(
                PROCESS, TENANT, "s-1", null,
                ThinkProcessStatus.RUNNING, ThinkProcessStatus.BLOCKED));

        verify(schedulerLogService).onBlocked(eq(RUN), anyString());
        verifyNoInteractions(schedulerService, megadodoService);
    }

    @Test
    void processFromAnotherTrigger_isIgnored() {
        // The listener sees every process termination in the system. A
        // hook- or tool-spawned process is none of its business.
        givenProcess(origin(TriggerKind.HOOK, "hook:process.completed:x", RUN),
                CloseReason.DONE);

        listener.onStatusChanged(closedEvent());

        verifyNoInteractions(schedulerLogService, schedulerService, megadodoService);
    }

    @Test
    void processWithoutTriggerOrigin_isIgnored() {
        // Normal user-driven spawn — no origin at all.
        givenProcess(null, CloseReason.DONE);

        listener.onStatusChanged(closedEvent());

        verifyNoInteractions(schedulerLogService, schedulerService, megadodoService);
    }

    @Test
    void schedulerOriginWithoutRunId_isRefusedRatherThanInvented() {
        // Would otherwise open a second, orphaned run log.
        givenProcess(origin(TriggerKind.SCHEDULER, SOURCE, null), CloseReason.DONE);

        listener.onStatusChanged(closedEvent());

        verify(schedulerLogService, never()).onTerminated(any(), any(), any());
        verifyNoInteractions(schedulerService, megadodoService);
    }

    @Test
    void unknownProcess_isIgnored() {
        when(thinkProcessService.findById(PROCESS)).thenReturn(Optional.empty());

        listener.onStatusChanged(closedEvent());

        verifyNoInteractions(schedulerLogService, schedulerService, megadodoService);
    }

    // ──── helpers ────────────────────────────────────────────────────

    private void givenProcess(@Nullable TriggerOrigin origin, @Nullable CloseReason reason) {
        ThinkProcessDocument doc = ThinkProcessDocument.builder()
                .tenantId(TENANT)
                .projectId(PROJECT)
                .sessionId("s-1")
                .name("nightly-run")
                .closeReason(reason)
                .triggerOrigin(origin)
                .build();
        doc.setId(PROCESS);
        when(thinkProcessService.findById(PROCESS)).thenReturn(Optional.of(doc));
    }

    private static TriggerOrigin origin(
            TriggerKind kind, String source, @Nullable String runId) {
        return TriggerOrigin.builder()
                .kind(kind)
                .source(source)
                .runId(runId)
                .runAs("marvin")
                .build();
    }

    private static ThinkProcessStatusChangedEvent closedEvent() {
        return new ThinkProcessStatusChangedEvent(
                PROCESS, TENANT, "s-1", null,
                ThinkProcessStatus.RUNNING, ThinkProcessStatus.CLOSED);
    }
}
