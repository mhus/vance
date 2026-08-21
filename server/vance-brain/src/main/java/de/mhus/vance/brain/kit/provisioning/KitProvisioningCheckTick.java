package de.mhus.vance.brain.kit.provisioning;

import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.ProjectStatus;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the provisioning check across the cluster, on the master pod.
 *
 * <p><b>Not an Ursa scheduler entry.</b> A scheduler entry triggers a
 * recipe, a workflow or a script, never a Java service, and adding a
 * fourth trigger kind for one consumer would be the wrong shape.
 *
 * <p><b>And not the pod's own projects either — that was the first
 * attempt and it was wrong.</b> Sweeping {@code findByHomeNode(self)}
 * looked elegant: a project is owned by one pod, so „only while it is
 * live" and „exactly once" collapse into one fact. They do — for the
 * projects that <em>have</em> an owner. Most do not. {@code EPHEMERAL} is
 * the default, nothing clears {@code homeNode} on shutdown, and the
 * boot-time self-pull reclaims only {@code PERMANENT} projects; so a
 * project sits pointing at a pod that died weeks ago and no live pod ever
 * sees it in that query. Provisioning does not need pod-local state, so
 * making ownership its precondition made it inert for the common case.
 *
 * <p>So: sweep everything, from the one pod holding the cluster-master
 * lease. Exactly the shape of {@code ClusterCleanupTick} — runs
 * everywhere, no-ops without the lease. „Exactly once" now comes from the
 * lease rather than from ownership, which is where it was available all
 * along.
 *
 * <p>A lease handover can still let two pods sweep once. That is covered
 * by the duplicate guard the notice needs regardless: an open item for the
 * same kit suppresses the second.
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
    private final TenantService tenantService;
    private final ClusterMasterService masterService;

    @Scheduled(
            initialDelayString =
                    "${vance.kits.provisioning.check-initial-delay:PT5M}",
            fixedDelayString = "${vance.kits.provisioning.check-interval:PT4H}")
    public void tick() {
        if (!properties.isCheckEnabled()) return;
        if (!masterService.isLocalPodMaster()) return;

        int swept = 0;
        int reported = 0;
        for (TenantDocument tenant : tenantService.all()) {
            for (ProjectDocument project : projectService.all(tenant.getName())) {
                if (project.getStatus() == ProjectStatus.CLOSED) continue;
                swept++;
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
        }
        if (reported > 0) {
            log.info("Provisioning check swept {} project(s), reported {}", swept, reported);
        } else {
            log.debug("Provisioning check swept {} project(s), nothing to report", swept);
        }
    }
}
