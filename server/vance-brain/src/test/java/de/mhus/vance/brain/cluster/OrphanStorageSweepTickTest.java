package de.mhus.vance.brain.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.storage.StorageOrphanCleanupService;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link OrphanStorageSweepTick#sweep(Instant)} directly so all
 * branches are reachable without the Spring scheduler — same pattern as
 * {@link ClusterCleanupTickTest} and {@link SessionStaleBindSweepTickTest}.
 */
class OrphanStorageSweepTickTest {

    private ClusterMasterService masterService;
    private StorageOrphanCleanupService cleanupService;
    private OrphanStorageSweepTick tick;

    @BeforeEach
    void setUp() throws Exception {
        masterService = mock(ClusterMasterService.class);
        cleanupService = mock(StorageOrphanCleanupService.class);
        tick = new OrphanStorageSweepTick(masterService, cleanupService);
        // Override @Value defaults so the test pins the values it asserts on.
        setField(tick, "gracePeriod", Duration.ofMinutes(90));
        setField(tick, "batchSize", 250);
    }

    @Test
    void sweep_passesConfiguredGraceAndBatchSize() {
        Instant now = Instant.parse("2026-06-12T08:00:00Z");
        when(cleanupService.sweepOnce(any(), any(), anyInt()))
                .thenReturn(new StorageOrphanCleanupService.CleanupResult(2, 3));

        StorageOrphanCleanupService.CleanupResult r = tick.sweep(now);

        assertThat(r.orphanArchivesDeleted()).isEqualTo(2);
        assertThat(r.orphanStorageDeleted()).isEqualTo(3);
        verify(cleanupService).sweepOnce(eq(now), eq(Duration.ofMinutes(90)), eq(250));
    }

    @Test
    void tick_noopWhenNotMaster() {
        when(masterService.isLocalPodMaster()).thenReturn(false);

        tick.tick();

        verify(cleanupService, never()).sweepOnce(any(), any(), anyInt());
    }

    @Test
    void tick_runsWhenMaster() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(cleanupService.sweepOnce(any(), any(), anyInt()))
                .thenReturn(new StorageOrphanCleanupService.CleanupResult(0, 0));

        tick.tick();

        verify(cleanupService).sweepOnce(any(), any(), anyInt());
    }

    @Test
    void tick_swallowsSweepFailure() {
        when(masterService.isLocalPodMaster()).thenReturn(true);
        when(cleanupService.sweepOnce(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("mongo down"));

        // Must not propagate — the scheduler thread would otherwise die.
        tick.tick();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
