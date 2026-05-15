package de.mhus.vance.brain.bootstrap;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.brain.project.ProjectManagerService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Reclaims pod-level state on startup.
 *
 * <p>Two concerns, both idempotent and safe under concurrent multi-pod
 * boots:
 * <ol>
 *   <li>Wipe stale {@code homeCluster} values on every project whose
 *       owning cluster node is no longer in the live registry. Every
 *       fresh pod boot picks a new node name (see
 *       {@code ClusterNodeNameGenerator}), so the previous incarnation's
 *       claims will never be inherited — they would otherwise block
 *       the current pod from claiming via the CAS predicate.</li>
 *   <li>Clear stale {@code boundConnectionId} fields on sessions under
 *       projects this pod owns — those refer to the previous incarnation
 *       and would block resume with a {@code 409}.</li>
 * </ol>
 *
 * <p>Listens on {@link ApplicationReadyEvent} with low precedence so it
 * runs <em>after</em> {@code ClusterService} has registered this pod's
 * row in {@code brain_pods} — otherwise we'd see our own node-name as
 * "not live" and wipe a claim we just took.
 *
 * <p>Project status is left alone. The first session-bind / wakeup-tick
 * after startup refreshes {@code homeCluster} + {@code claimedAt}
 * through {@link ProjectManagerService#claimForLocalPod} via the
 * regular lifecycle path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectStartupReclaimer {

    private final ProjectService projectService;
    private final ProjectManagerService projectManager;
    private final ClusterService clusterService;
    private final SessionService sessionService;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    void reclaim() {
        clearStaleClusterClaims();
        clearStaleSessionBindings();
    }

    /**
     * Idempotent bulk-cleanup of project claims whose owning cluster
     * node has dropped out of the live registry. Two pods racing here
     * converge on the same final state: the {@code updateMulti} filter
     * matches the same set of stale documents (whatever pods are live
     * is observed from the same Mongo), and the update body is
     * identical. {@code homeCluster=null} is the unowned state.
     */
    private void clearStaleClusterClaims() {
        Set<String> liveClusters = new HashSet<>(clusterService.liveClusterNodeNames());
        // Belt-and-suspenders: always include this pod's node name even
        // if the brain_pods registration is still in flight.
        String selfCluster = clusterService.selfNodeName();
        if (selfCluster != null && !selfCluster.isBlank()) {
            liveClusters.add(selfCluster);
        }
        long cleared = projectService.clearStaleHomeClusters(liveClusters);
        if (cleared > 0) {
            log.info("ProjectStartupReclaimer: cleared {} stale home-cluster claim(s); live={}",
                    cleared, liveClusters);
        } else {
            log.info("ProjectStartupReclaimer: no stale home-cluster claims; live={}",
                    liveClusters);
        }
    }

    /**
     * Sessions under projects this pod currently owns (i.e. RUNNING +
     * {@code homeCluster == self}) may still carry {@code boundConnectionId}
     * values from the previous incarnation — wipe those so the next
     * client connect can resume cleanly. Other pods' sessions are not
     * touched.
     */
    private void clearStaleSessionBindings() {
        List<ProjectDocument> mine = projectManager.projectsOwnedByLocalPod();
        if (mine.isEmpty()) {
            log.info("ProjectStartupReclaimer: no sessions to unbind (no projects owned)");
            return;
        }
        List<String> projectNames = mine.stream().map(ProjectDocument::getName).toList();
        long n = sessionService.unbindAllForProjects(projectNames);
        log.info("ProjectStartupReclaimer: {} project(s) owned by this pod, {} stale binding(s) cleared",
                projectNames.size(), n);
    }
}
