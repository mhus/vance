package de.mhus.vance.brain.lease;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import de.mhus.vance.shared.lease.ResourceLeaseHolderDocument;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The brain-side reaper round (§5 of
 * {@code specification/public/lease-system.md}): idle reap, dead-pod reap
 * and counter reconcile run in the shared {@link ResourceLeaseService};
 * this component drives them and hands every reaped holder to the
 * {@link LeaseRevocationDispatcher} (the {@code podBound} matrix).
 */
@Component
@Slf4j
public class ResourceLeaseSweeper {

    private final ResourceLeaseService leaseService;
    private final LeaseRevocationDispatcher dispatcher;
    private final ClusterService cluster;

    /** Counters quiet for this window may be reconciled — must comfortably exceed every waitMs. */
    @Value("${vance.lease.reconcile-safety-ms:60000}")
    private long reconcileSafetyMs;

    public ResourceLeaseSweeper(
            ResourceLeaseService leaseService, LeaseRevocationDispatcher dispatcher, ClusterService cluster) {
        this.leaseService = leaseService;
        this.dispatcher = dispatcher;
        this.cluster = cluster;
    }

    @Scheduled(
            fixedDelayString = "${vance.lease.reap-interval:PT30S}",
            initialDelayString = "${vance.lease.reap-interval:PT30S}")
    public void sweep() {
        try {
            sweepRound();
        } catch (RuntimeException e) {
            // A failed round is survivable — the lazy idle sweep at
            // acquire and the TTL of the holders cover the gap.
            log.warn("resource lease sweep failed: {}", e.toString());
        }
    }

    /** Extracted so tests can drive a round deterministically. */
    void sweepRound() {
        dispatch(leaseService.reapIdle(null));
        reapDeadPods();
        leaseService.reconcile(Instant.now().minus(Duration.ofMillis(reconcileSafetyMs)));
    }

    private void reapDeadPods() {
        String selfPod = cluster.selfPodId();
        Set<String> pods = new HashSet<>();
        for (ResourceLeaseHolderDocument holder : leaseService.listHolders()) {
            pods.add(holder.getPod());
        }
        for (String pod : pods) {
            if (pod.equals(selfPod) || cluster.resolveEndpointByPodId(pod).isPresent()) {
                continue;
            }
            dispatch(leaseService.reapPod(pod));
        }
    }

    private void dispatch(List<ResourceLeaseHolderDocument> holders) {
        dispatcher.dispatch(holders.stream().map(LeaseHolderInfo::of).toList());
    }
}
