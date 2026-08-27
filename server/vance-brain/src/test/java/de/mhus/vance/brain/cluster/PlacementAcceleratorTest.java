package de.mhus.vance.brain.cluster;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The event-to-round path and the throttle that protects the REST publisher. */
class PlacementAcceleratorTest {

    private final ClusterDistributorTick distributor = mock(ClusterDistributorTick.class);
    private final ClusterProperties properties = new ClusterProperties();
    private final PlacementAccelerator accelerator =
            new PlacementAccelerator(distributor, properties);

    @Test
    void anEvent_runsARoundImmediately() {
        accelerator.onPlacementInputChanged(new PlacementInputChangedEvent("pod registered: gpu-01"));

        verify(distributor).distribute();
    }

    @Test
    void aSecondEventInsideTheWindow_isDropped() {
        accelerator.onPlacementInputChanged(new PlacementInputChangedEvent("first"));
        accelerator.onPlacementInputChanged(new PlacementInputChangedEvent("second"));

        verify(distributor, times(1)).distribute();
    }

    @Test
    void withoutAWindow_everyEventRunsARound() {
        // The floor exists for the externally reachable PATCH, not because a
        // round is expensive — so it has to be switchable off.
        properties.getMaster().setAccelerateMinInterval(Duration.ZERO);

        accelerator.onPlacementInputChanged(new PlacementInputChangedEvent("first"));
        accelerator.onPlacementInputChanged(new PlacementInputChangedEvent("second"));

        verify(distributor, times(2)).distribute();
    }

    @Test
    void aFailedRound_doesNotEscapeAndDoesNotStopLaterRounds() {
        // This runs on the async executor, where an escaping exception reaches
        // nothing but an uncaught-exception handler.
        doThrow(new IllegalStateException("mongo down")).when(distributor).distribute();
        properties.getMaster().setAccelerateMinInterval(Duration.ZERO);

        accelerator.onPlacementInputChanged(new PlacementInputChangedEvent("first"));
        accelerator.onPlacementInputChanged(new PlacementInputChangedEvent("second"));

        verify(distributor, times(2)).distribute();
    }

    @Test
    void theThrottle_countsAttemptsNotSuccesses() {
        // A failing round still consumes the window. Retrying on every
        // following event instead would turn a broken Mongo into a hot loop —
        // and the periodic tick is already the retry that belongs there.
        doThrow(new IllegalStateException("mongo down")).when(distributor).distribute();

        accelerator.onPlacementInputChanged(new PlacementInputChangedEvent("first"));
        accelerator.onPlacementInputChanged(new PlacementInputChangedEvent("second"));

        verify(distributor, times(1)).distribute();
    }
}
