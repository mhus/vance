package de.mhus.vance.brain.lease;

import de.mhus.vance.api.insights.LeaseCounterInsightsDto;
import de.mhus.vance.api.insights.LeaseHolderInsightsDto;
import de.mhus.vance.api.insights.LeaseInsightsDto;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.cluster.BrainPodDocument;
import de.mhus.vance.shared.lease.LeaseCounter;
import de.mhus.vance.shared.lease.LeaseHolderInfo;
import de.mhus.vance.shared.lease.ResourceLeaseHolderDocument;
import de.mhus.vance.shared.lease.ResourceLeaseService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Operator actions on resource leases — the brain half of
 * {@code specification/public/lease-system.md} §9. A revoke here is a reap
 * on an operator's decision and therefore runs the same
 * {@link LeaseRevocationDispatcher} as the sweeper: freeing only the slot
 * would leave the resource running (§5).
 *
 * <p>Two callers: the tenant admin through Insights (tenant-scoped, every
 * method takes the tenant) and the admin shell through
 * {@code /internal/lease/admin} (unscoped — {@code tenantId == null}).
 */
@Service
public class LeaseAdminService {

    private final ResourceLeaseService leaseService;
    private final LeaseRevocationDispatcher dispatcher;
    private final ResourceLeaseSweeper sweeper;
    private final ClusterService cluster;

    public LeaseAdminService(
            ResourceLeaseService leaseService,
            LeaseRevocationDispatcher dispatcher,
            ResourceLeaseSweeper sweeper,
            ClusterService cluster) {
        this.leaseService = leaseService;
        this.dispatcher = dispatcher;
        this.sweeper = sweeper;
        this.cluster = cluster;
    }

    /** Holders and tenant-owned counters of one tenant. */
    public LeaseInsightsDto snapshot(String tenantId) {
        List<ResourceLeaseHolderDocument> holders = leaseService.listHolders(tenantId);
        Map<String, String> nodeNames = cluster.listCluster().stream()
                .filter(p -> p.getPodId() != null && p.getNodeName() != null)
                .collect(Collectors.toMap(BrainPodDocument::getPodId, BrainPodDocument::getNodeName, (a, b) -> a));
        Map<String, Boolean> alive = new HashMap<>();
        Predicate<String> isAlive = pod -> alive.computeIfAbsent(pod, this::isPodAlive);

        Map<String, Long> booked = bookedPerCounter(leaseService.listHolders());
        return LeaseInsightsDto.builder()
                .holders(holders.stream()
                        .sorted(Comparator.comparing(
                                ResourceLeaseHolderDocument::getAcquiredAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(h -> toHolderDto(h, nodeNames::get, isAlive.test(h.getPod())))
                        .toList())
                .counters(leaseService.listCounters(tenantId).stream()
                        .sorted(Comparator.comparing(LeaseCounter::key))
                        .map(c -> toCounterDto(c, booked.getOrDefault(c.key(), 0L)))
                        .toList())
                .build();
    }

    /**
     * Revokes one holder and dispatches its handler synchronously.
     *
     * @param tenantId when set, only a holder of this tenant is revoked
     * @return the revoked holder, empty when there was none to revoke
     */
    public Optional<LeaseHolderInfo> revoke(String token, @Nullable String tenantId) {
        Optional<LeaseHolderInfo> revoked = leaseService.revoke(token, tenantId).map(LeaseHolderInfo::of);
        revoked.ifPresent(info -> dispatcher.dispatch(List.of(info)));
        return revoked;
    }

    /** Revokes every holder of one pod — alive or not — and dispatches their handlers. */
    public List<LeaseHolderInfo> revokePod(String podId) {
        List<LeaseHolderInfo> revoked =
                leaseService.reapPod(podId).stream().map(LeaseHolderInfo::of).toList();
        dispatcher.dispatch(revoked);
        return revoked;
    }

    /** One reaper round now instead of at the next schedule: idle reap, dead-pod reap, reconcile. */
    public void sweep() {
        sweeper.sweepRound();
    }

    /** Same liveness criterion as the sweeper and the dispatcher — {@link LeasePodLiveness}. */
    private boolean isPodAlive(String podId) {
        return LeasePodLiveness.isAlive(cluster, podId);
    }

    /** Counter key → number of holders that book it; the reference value reconcile corrects towards. */
    static Map<String, Long> bookedPerCounter(List<ResourceLeaseHolderDocument> holders) {
        Map<String, Long> booked = new HashMap<>();
        for (ResourceLeaseHolderDocument holder : holders) {
            for (String key : holder.getLevels()) {
                booked.merge(key, 1L, Long::sum);
            }
        }
        return booked;
    }

    static LeaseHolderInsightsDto toHolderDto(
            ResourceLeaseHolderDocument holder, Function<String, @Nullable String> nodeName, boolean podAlive) {
        Map<String, String> payload = new LinkedHashMap<>();
        if (holder.getPayload() != null) {
            new TreeMap<>(holder.getPayload()).forEach((k, v) -> payload.put(k, String.valueOf(v)));
        }
        return LeaseHolderInsightsDto.builder()
                .token(holder.getToken())
                .state(holder.getState())
                .type(holder.getType())
                .resource(holder.getResource())
                .holderRef(holder.getHolderRef())
                .projectId(holder.getProjectId())
                .userId(holder.getUserId())
                .pod(holder.getPod())
                .podNodeName(nodeName.apply(holder.getPod()))
                .podAlive(podAlive)
                .podBound(holder.isPodBound())
                .ttlSeconds(holder.getTtlSeconds())
                .acquiredAt(holder.getAcquiredAt())
                .lastSeenAt(holder.getLastSeenAt())
                .payload(payload)
                .build();
    }

    static LeaseCounterInsightsDto toCounterDto(LeaseCounter counter, long booked) {
        return LeaseCounterInsightsDto.builder()
                .resource(counter.resource())
                .level(counter.level())
                .scope(counter.scopeWithinTenant())
                .count(counter.count())
                .booked(booked)
                .updatedAt(counter.updatedAt())
                .build();
    }
}
