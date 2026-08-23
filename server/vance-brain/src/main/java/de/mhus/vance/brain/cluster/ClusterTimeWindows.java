package de.mhus.vance.brain.cluster;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The cluster's time windows — and, the reason this class exists at all, the
 * <em>relationship</em> between them.
 *
 * <p>Five durations govern how long a piece of cluster knowledge stays true.
 * They were configured independently, read independently, and one of them
 * (the lease TTL) was silently treated as the outer bound of all the others.
 * It is not, and the defaults say so out loud:
 *
 * <table border="1">
 *   <caption>The ladder</caption>
 *   <tr><th>Window</th><th>Property</th><th>Default</th><th>Answers</th></tr>
 *   <tr><td>heartbeat</td><td>{@code vance.cluster.heartbeat-interval}</td><td>1 min</td>
 *       <td>how often a pod republishes its address and its liveness</td></tr>
 *   <tr><td>pod liveness</td><td>{@code vance.cluster.stale-after}</td><td>2 min</td>
 *       <td><b>is that pod still alive</b></td></tr>
 *   <tr><td>lease renewal</td><td>{@code vance.cluster.lease.renew-interval}</td><td>1 min</td>
 *       <td>how often a holder refreshes the projects it owns</td></tr>
 *   <tr><td>lease TTL</td><td>{@code vance.cluster.lease.ttl}</td><td>5 min</td>
 *       <td><b>does that pod still own the project</b></td></tr>
 *   <tr><td>row retention</td><td>{@code vance.cluster.cleanup.after}</td><td>1 h</td>
 *       <td>when a dead pod's registry row is purged</td></tr>
 * </table>
 *
 * <h2>Two questions, two clocks — deliberately not ordered</h2>
 *
 * <p>"Is the pod alive" and "does the pod own this project" are different
 * questions and they are allowed to have different answers. The lease TTL is
 * generous on purpose: a holder must survive a GC pause or a Mongo hiccup
 * without losing projects it is still serving. Pod liveness is tight on
 * purpose: a dead address has to stop being dialled fast, because dialling it
 * costs a connect timeout on the user's request.
 *
 * <p>So there is no invariant {@code leaseTtl <= podLiveness} and none is
 * introduced here. What follows instead is a rule for readers:
 *
 * <blockquote><b>Every routing answer passes both gates.</b> The lease says
 * who owns the project; the pod registry says whether that owner still means
 * anything. Answering with only one of them is what let a {@code kill -9}'d
 * pod keep receiving traffic for up to {@code leaseTtl} — the lease was still
 * valid, so nobody asked whether the pod was alive.</blockquote>
 *
 * <p>And a bound for anything derived from those gates: a cached routing
 * answer is only as true as the <em>shortest</em> window it was derived from,
 * hence {@link #routingAnswerMaxAge()}. A cache that outlives its inputs is
 * not a cache, it is a third opinion.
 *
 * <h2>What is checked at boot</h2>
 *
 * <p>Only the relations that make a window meaningless when violated, and only
 * as a warning: a duration typo degrades the cluster, it does not corrupt it,
 * and refusing to boot every pod over one is the worse failure mode.
 *
 * <p>See {@code specification/cluster-project-management.md} §2a.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClusterTimeWindows {

    private final ClusterProperties properties;

    /** How often this pod republishes its address and liveness. */
    public Duration heartbeatInterval() {
        return properties.getHeartbeatInterval();
    }

    /**
     * How long a pod stays "alive" to an observer without a heartbeat — the
     * gate on <em>is this endpoint still worth dialling</em>.
     */
    public Duration podLiveness() {
        return properties.getStaleAfter();
    }

    /** How often this pod refreshes the leases it holds. */
    public Duration leaseRenewInterval() {
        return properties.getLease().getRenewInterval();
    }

    /**
     * How long project ownership survives without renewal — the gate on
     * <em>does this pod still own the project</em>. Read-side policy: raising
     * it takes effect immediately instead of waiting for leases to roll over.
     */
    public Duration leaseTtl() {
        return properties.getLease().getTtl();
    }

    /** How old a pod's registry row may get before the cleanup sweep purges it. */
    public Duration podRowRetention() {
        return properties.getCleanup().getAfter();
    }

    /**
     * How long a <em>derived</em> routing answer ("project X is served at
     * host:port") may be reused without re-deriving it: the smaller of the two
     * gates it came from. Neither gate alone bounds it — the lease can outlive
     * the pod, and the pod can outlive its claim.
     */
    public Duration routingAnswerMaxAge() {
        Duration liveness = podLiveness();
        Duration lease = leaseTtl();
        return liveness.compareTo(lease) <= 0 ? liveness : lease;
    }

    @PostConstruct
    void warnOnInvertedWindows() {
        checkOrder("heartbeat-interval", heartbeatInterval(), "stale-after", podLiveness(),
                "every pod would count as stale between two of its own beats");
        checkOrder("lease.renew-interval", leaseRenewInterval(), "lease.ttl", leaseTtl(),
                "a holder would lose its projects between two of its own renewals");
        checkOrder("stale-after", podLiveness(), "cleanup.after", podRowRetention(),
                "pod rows would be purged before anyone had a chance to see them as stale");
        log.debug("ClusterTimeWindows: heartbeat={} liveness={} leaseRenew={} leaseTtl={} "
                        + "rowRetention={} routingAnswerMaxAge={}",
                heartbeatInterval(), podLiveness(), leaseRenewInterval(), leaseTtl(),
                podRowRetention(), routingAnswerMaxAge());
    }

    private static void checkOrder(String shorterName, Duration shorter,
                                   String longerName, Duration longer,
                                   String consequence) {
        if (shorter.compareTo(longer) >= 0) {
            log.warn("Cluster time windows inverted: vance.cluster.{}={} is not shorter than "
                            + "vance.cluster.{}={} — {}",
                    shorterName, shorter, longerName, longer, consequence);
        }
    }
}
