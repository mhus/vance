package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the provisioning check over the projects this pod owns.
 *
 * <p><b>Not an Ursa scheduler entry, and that was a course correction.</b>
 * The plan had this hanging off {@code UrsaScheduler} for its four
 * guarantees — project-active gating, cross-pod fire claim, overlap
 * policy, agent protection. It cannot: a scheduler entry triggers a
 * recipe, a workflow or a script, never a Java service. Adding a fourth
 * trigger kind for one consumer would have been the wrong shape.
 *
 * <p>What replaced it turned out smaller <em>and</em> better. Iterating
 * {@code findByHomeNode} gives the two guarantees that mattered for free:
 * a project is owned by one pod, so „only while it is live" and
 * „exactly once" are the same fact. Nothing has to be claimed. The price
 * is that the interval is a property rather than a per-project document —
 * which is what „every four hours" wanted to be anyway.
 *
 * <p>Two pods can briefly both own a project during a handover, so the
 * check can run twice. That is covered by the duplicate guard the notice
 * needs regardless: an open item for the same kit suppresses the second.
 *
 * <p>Reports only. Installing is the other triggers' business (see
 * {@link KitProvisioningCheck}).
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(KitProvisioningProperties.class)
@Slf4j
public class KitProvisioningCheckTick {

    private final KitProvisioningProperties properties;
    private final KitProvisioningCheck check;
    private final ProjectService projectService;
    private final ClusterService clusterService;

    @Scheduled(
            initialDelayString =
                    "${vance.kits.provisioning.check-initial-delay:PT5M}",
            fixedDelayString = "${vance.kits.provisioning.check-interval:PT4H}")
    public void tick() {
        if (!properties.isCheckEnabled()) return;

        String node = clusterService.selfNodeName();
        if (node == null || node.isBlank()) return;

        List<ProjectDocument> mine = projectService.findByHomeNode(node);
        int reported = 0;
        for (ProjectDocument project : mine) {
            try {
                KitProvisioningCheck.Report report =
                        check.check(project.getTenantId(), project.getName());
                reported += report.reported().size();
            } catch (RuntimeException e) {
                // One project's broken configuration must not end the sweep
                // over the others.
                log.warn("Provisioning check of {}/{} failed: {}",
                        project.getTenantId(), project.getName(), e.toString());
            }
        }
        if (reported > 0) {
            log.info("Provisioning check swept {} project(s) of node '{}', reported {}",
                    mine.size(), node, reported);
        } else {
            log.debug("Provisioning check swept {} project(s) of node '{}', nothing to report",
                    mine.size(), node);
        }
    }
}
