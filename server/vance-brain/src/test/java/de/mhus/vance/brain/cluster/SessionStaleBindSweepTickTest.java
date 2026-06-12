package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.session.SessionService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link SessionStaleBindSweepTick#sweep(Instant)} directly so all
 * branches are reachable without the Spring scheduler — same pattern as
 * {@link ClusterCleanupTickTest}.
 */
class SessionStaleBindSweepTickTest {

    private ClusterMasterService masterService;
    private SessionService sessionService;
    private SessionStaleBindSweepTick tick;

    @BeforeEach
    void setUp() {
        masterService = mock(ClusterMasterService.class);
        sessionService = mock(SessionService.class);
        lenient().when(sessionService.bindStaleAfter()).thenReturn(Duration.ofMinutes(2));
        tick = new SessionStaleBindSweepTick(masterService, sessionService);
    }

    @Test
    void sweep_passesNowMinusBindStaleAfterAsCutoff() {
        Instant now = Instant.parse("2026-06-12T07:00:00Z");
        when(sessionService.unbindStaleConnections(any(Instant.class))).thenReturn(5L);

        long n = tick.sweep(now);

        assertThat(n).isEqualTo(5L);
        verify(sessionService).unbindStaleConnections(now.minus(Duration.ofMinutes(2)));
    }

    @Test
    void sweep_respectsConfiguredBindStaleAfter() {
        when(sessionService.bindStaleAfter()).thenReturn(Duration.ofMinutes(10));
        Instant now = Instant.parse("2026-06-12T07:00:00Z");
        when(sessionService.unbindStaleConnections(any(Instant.class))).thenReturn(0L);

        tick.sweep(now);

        verify(sessionService).unbindStaleConnections(now.minus(Duration.ofMinutes(10)));
    }

    @Test
    void tick_noopWhenNotMaster() {
        when(masterService.isLocalPodMaster()).thenReturn(false);

        tick.tick();

        verify(sessionService, never()).unbindStaleConnections(any(Instant.class));
    }

    @Test
    void tick_runsWhenMaster() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(sessionService.unbindStaleConnections(any(Instant.class))).thenReturn(0L);

        tick.tick();

        verify(sessionService).unbindStaleConnections(any(Instant.class));
    }

    @Test
    void tick_swallowsSweepFailure() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(sessionService.unbindStaleConnections(any(Instant.class)))
                .thenThrow(new RuntimeException("mongo down"));

        // Must not propagate — the scheduler thread would otherwise be killed.
        tick.tick();
    }
}
