package de.mhus.vance.brain.thinkengine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link DelegationDeadlockWatchdog}. Mongo / lane
 * wiring is mocked; we only verify the sweep contract: skip-when-not-master,
 * close each stalled worker with STOPPED, return the count actually closed.
 */
class DelegationDeadlockWatchdogTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-06-19T20:00:00Z");

    private ClusterMasterService master;
    private ThinkProcessService thinkProcessService;
    private DelegationDeadlockWatchdog watchdog;

    @BeforeEach
    void setUp() {
        master = mock(ClusterMasterService.class);
        thinkProcessService = mock(ThinkProcessService.class);
        watchdog = new DelegationDeadlockWatchdog(master, thinkProcessService, STALE_AFTER);
    }

    @Test
    void tick_skippedWhenNotMaster() {
        when(master.isLocalPodMaster()).thenReturn(false);

        watchdog.tick();

        verify(thinkProcessService, never()).findStalledDelegatedWorkers(any());
        verify(thinkProcessService, never()).closeProcess(any(), any());
    }

    @Test
    void sweep_closesEachStalledWorkerWithStopped() {
        ThinkProcessDocument w1 = worker("worker-1", "parent-A");
        ThinkProcessDocument w2 = worker("worker-2", "parent-B");
        when(thinkProcessService.findStalledDelegatedWorkers(any()))
                .thenReturn(List.of(w1, w2));
        when(thinkProcessService.closeProcess(any(), eq(CloseReason.STOPPED)))
                .thenReturn(true);

        int closed = watchdog.sweep(NOW);

        assertThat(closed).isEqualTo(2);
        verify(thinkProcessService).closeProcess("worker-1", CloseReason.STOPPED);
        verify(thinkProcessService).closeProcess("worker-2", CloseReason.STOPPED);
    }

    @Test
    void sweep_usesConfiguredStaleAfterForCutoff() {
        when(thinkProcessService.findStalledDelegatedWorkers(any())).thenReturn(List.of());

        watchdog.sweep(NOW);

        verify(thinkProcessService).findStalledDelegatedWorkers(NOW.minus(STALE_AFTER));
    }

    @Test
    void sweep_returnsZeroOnEmpty() {
        when(thinkProcessService.findStalledDelegatedWorkers(any())).thenReturn(List.of());

        assertThat(watchdog.sweep(NOW)).isZero();
        verify(thinkProcessService, never()).closeProcess(any(), any());
    }

    @Test
    void sweep_skipsAlreadyClosedWorkersInCount() {
        // closeProcess returns false when the row was already CLOSED
        // (idempotent). The count reflects rows we actually transitioned.
        ThinkProcessDocument w1 = worker("worker-1", "parent-A");
        ThinkProcessDocument w2 = worker("worker-2", "parent-B");
        when(thinkProcessService.findStalledDelegatedWorkers(any()))
                .thenReturn(List.of(w1, w2));
        when(thinkProcessService.closeProcess("worker-1", CloseReason.STOPPED)).thenReturn(true);
        when(thinkProcessService.closeProcess("worker-2", CloseReason.STOPPED)).thenReturn(false);

        assertThat(watchdog.sweep(NOW)).isEqualTo(1);
    }

    private static ThinkProcessDocument worker(String id, String parentId) {
        return ThinkProcessDocument.builder()
                .id(id)
                .tenantId("mhus")
                .sessionId("sess_abc")
                .parentProcessId(parentId)
                .status(ThinkProcessStatus.BLOCKED)
                .build();
    }
}
