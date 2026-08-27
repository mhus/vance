package de.mhus.vance.brain.cluster;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs a placement round right after something that decides placement changed,
 * instead of waiting out the {@code distributorInterval}.
 *
 * <p>Without this, a project that nothing can host waits up to a full tick
 * after the pod it was waiting for appears — which is precisely the case the
 * demand signal exists to create ({@code
 * specification/cluster-project-management.md} §5b): an external controller
 * reads the demand, provisions a pod, and then the interval is the whole
 * remaining latency. The pod that observed the change already knows enough to
 * close that gap.
 *
 * <p>Three deliberate choices:
 *
 * <ul>
 *   <li><b>No master check.</b> {@link ClusterDistributorTick#distribute()} is
 *       race-free against parallel runs — the CAS in {@code
 *       ProjectService.claim} lets exactly one pod win each orphan — so the
 *       observer may run it. That independence is the point: on a cold cluster
 *       the master lease may not be held by anyone yet, and that is exactly
 *       when the first pods are registering.
 *   <li><b>Reuses {@code distribute()}.</b> A second pick loop here is how the
 *       two copies drifted apart before
 *       ({@code planning/project-placement-labels.md} §1.3).
 *   <li><b>Does not notify demand.</b> {@code PlacementDemandNotifier} keeps
 *       its fingerprint per process and is driven by the master's tick; an
 *       extra round from an arbitrary pod would report against the wrong
 *       fingerprint. Demand is a level, and the tick is what samples it.
 * </ul>
 *
 * <p>Gated by the same property as the distributor: a pod told not to master
 * does not distribute, on a schedule or otherwise.
 */
@Component
@ConditionalOnProperty(name = "vance.cluster.master.enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class PlacementAccelerator {

    private final ClusterDistributorTick distributor;
    private final ClusterProperties properties;

    /** Start of the last round this bean ran; {@code null} until the first. */
    private volatile @Nullable Instant lastRun;

    /**
     * Reacts to {@link PlacementInputChangedEvent} with one placement round.
     *
     * <p>{@code @Async} because the publishers are a boot listener and a REST
     * handler: a placement round walks the orphan list and brings projects up
     * across the cluster, which must not sit inside either.
     *
     * <p>Throttled, and the throttle is about the REST path rather than the
     * cost — an empty round is one indexed query. Two of the three publishers
     * are externally reachable {@code /internal/} endpoints, so a controller
     * writing labels in a loop would otherwise turn every request into a
     * cluster-wide round. Dropping the trailing event of a burst is acceptable
     * because the periodic tick is the floor underneath: the worst case is the
     * latency this class removes, not a project that never gets placed.
     */
    @Async
    @EventListener
    public void onPlacementInputChanged(PlacementInputChangedEvent event) {
        Duration minInterval = properties.getMaster().getAccelerateMinInterval();
        Instant previous = lastRun;
        Instant now = Instant.now();
        if (previous != null && Duration.between(previous, now).compareTo(minInterval) < 0) {
            log.debug("PlacementAccelerator: skipping round ({}) — last one was {} ago, "
                            + "minimum is {}",
                    event.reason(), Duration.between(previous, now), minInterval);
            return;
        }
        lastRun = now;
        log.debug("PlacementAccelerator: round triggered by {}", event.reason());
        try {
            distributor.distribute();
        } catch (RuntimeException e) {
            // Same handling as the tick: a failed round is a warning, never an
            // exception nobody catches — this runs on the async executor, where
            // an escape would only reach an uncaught-exception handler.
            log.warn("PlacementAccelerator: round failed ({}): {}", event.reason(), e.toString());
        }
    }
}
