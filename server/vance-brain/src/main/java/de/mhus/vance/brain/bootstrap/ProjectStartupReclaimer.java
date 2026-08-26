package de.mhus.vance.brain.bootstrap;

import de.mhus.vance.brain.cluster.ClusterProperties;
import de.mhus.vance.brain.project.ProjectLifecycleService;
import de.mhus.vance.brain.project.ProjectOwnerRequirementService;
import de.mhus.vance.brain.project.ProjectSelfPullService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * The boot half of the project self-pull: re-derive which projects still need
 * an owner, then ask {@link ProjectSelfPullService} for one pass.
 *
 * <p>The pass itself lives in that service because the periodic variant asks
 * the same question later, and a class called "StartupReclaimer" cannot carry a
 * recurring tick. What is left here is the boot-specific part: the
 * {@code ownerRequired} re-derivation, and the
 * {@code vance.cluster.self-pull.boot} switch that lets a pod come up
 * <em>without</em> taking anything, waiting to be assigned instead
 * ({@code planning/project-placement-labels.md} §4a).
 *
 * <p><b>No stale-claim wipe any more.</b> This used to start by nulling
 * {@code homeNode} on every project whose owning node had dropped out of the
 * live registry. With ownership expressed as a lease
 * ({@code planning/project-ownership-lease-design.md} §3) there is nothing to
 * wipe: an un-renewed lease is expired, an expired lease blocks nobody, and the
 * claim CAS takes it over on the spot. The old wipe was also the only
 * reconciliation there was, and it ran <em>only here</em> — so a pod that
 * crashed and restarted inside the stale window kept its claims forever, and a
 * long-lived cluster never reconciled at all.
 *
 * <p>Stale {@code boundConnectionId} cleanup is handled in two places
 * instead of here:
 * <ul>
 *   <li>{@link ProjectLifecycleService#bring} unbinds at the moment a
 *       project transitions from non-RUNNING to RUNNING on this pod —
 *       covers every claim path (self-pull, distributor, locator,
 *       direct-spawn) and is the latency-critical fast path for the
 *       next reconnect.</li>
 *   <li>{@code SessionStaleBindSweepTick} sweeps cluster-wide on the
 *       master pod — catches every session, including those for
 *       projects no pod currently owns ({@code _user_*}, archived).</li>
 * </ul>
 *
 * <p>Listens on {@link ApplicationReadyEvent}. It used to claim that its
 * {@code LOWEST_PRECEDENCE} made it run after {@code ClusterService} had
 * registered this pod — with the reason that the pulled projects would then be
 * reported in the next heartbeat. Both halves were wrong: an
 * {@code @EventListener} without an {@code @Order} gets {@code LOWEST_PRECEDENCE}
 * as well, so the order fell back to bean discovery; and the heartbeat
 * recomputes {@code activeProjects} from Mongo on every beat, so it never
 * mattered anyway. The precondition that does matter is now stated and enforced
 * in {@link ProjectSelfPullService#readyToPull()}.
 *
 * <p>Project status is left alone. It expresses intent ("should be live"), not
 * placement, and the lease answers placement.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectStartupReclaimer {

    private final ProjectOwnerRequirementService ownerRequirementService;
    private final ClusterProperties clusterProperties;
    private final ProjectSelfPullService selfPullService;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    void reclaim() {
        releaseStalePins();
        if (!clusterProperties.getSelfPull().isBoot()) {
            log.info("ProjectStartupReclaimer: boot self-pull disabled "
                    + "(vance.cluster.self-pull.boot=false) — this pod waits to be assigned");
            return;
        }
        if (!selfPullService.readyToPull()) {
            return;
        }
        selfPullService.pullOnce("boot");
    }

    /**
     * Re-derives {@code ownerRequired} for the pinned projects before pulling
     * any of them in, so a project whose last scheduler was deleted while this
     * brain was down does not get claimed for work that no longer exists.
     *
     * <p>Runs first for that reason — the self-pull immediately below reads the
     * flag this corrects.
     */
    private void releaseStalePins() {
        try {
            int released = ownerRequirementService.releaseNoLongerQualifying();
            if (released > 0) {
                log.info("ProjectStartupReclaimer: released {} project(s) that no longer "
                        + "hold waiting work", released);
            }
        } catch (RuntimeException e) {
            // Worst case we pull in a project that has nothing to do — wasteful,
            // not wrong. Never a reason to fail the boot.
            log.warn("ProjectStartupReclaimer: owner-requirement re-derivation failed: {}",
                    e.toString());
        }
    }

}
