package de.mhus.vance.brain.project;

import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-brings RUNNING projects whose home cluster has died
 * and that carry owner-pod-bound engine state ({@code requiresOwnerPod=true}).
 *
 * <p>Selector: {@code status=RUNNING AND homeCluster=null AND
 * requiresOwnerPod=true}. The {@code homeCluster=null} filter is fed
 * by {@code ProjectStartupReclaimer.clearStaleClusterClaims()} on every
 * pod boot — a node that drops out of the live registry has its claims
 * wiped, and this tick then notices the orphans and re-claims them on
 * whatever live pod picks them up first.
 *
 * <p>Race-freeness comes from the CAS in
 * {@link ProjectService#claim(String, String, String, java.util.Set)}.
 * Several pods may see the same orphan and call {@code bring()} in
 * parallel; the CAS lets exactly one win, the others see a redirect
 * (handled silently — we just skip).
 *
 * <p>Per-tick limit ({@code vance.cluster.projectWakeup.maxPerTick})
 * caps the burst when a pod recovers from a long outage and many
 * projects need re-claiming. Default 10 per tick (60s interval) gives
 * 600 projects/minute of catch-up rate per pod, with N pods that's
 * {@code N×600}.
 *
 * <p>Disabled by default in test profiles via
 * {@code vance.cluster.projectWakeup.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "vance.cluster.projectWakeup.enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ProjectWakeupTick {

    private final ProjectService projectService;
    private final ProjectLifecycleService lifecycleService;

    @Value("${vance.cluster.projectWakeup.maxPerTick:10}")
    private int maxPerTick;

    @Scheduled(fixedDelayString = "${vance.cluster.projectWakeup.intervalMs:60000}",
            initialDelayString = "${vance.cluster.projectWakeup.initialDelayMs:30000}")
    public void tick() {
        List<ProjectDocument> orphans = projectService.findRunningOrphansRequiringOwnerPod(maxPerTick);
        if (orphans.isEmpty()) return;
        int won = 0;
        int skipped = 0;
        for (ProjectDocument p : orphans) {
            try {
                lifecycleService.bring(p.getTenantId(), p.getName());
                won++;
            } catch (ProjectManagerService.ClaimRejectedException ex) {
                // Another pod won the race for this orphan — fine, move on.
                skipped++;
            } catch (RuntimeException ex) {
                log.warn("ProjectWakeupTick: bring failed for '{}/{}': {}",
                        p.getTenantId(), p.getName(), ex.toString());
                skipped++;
            }
        }
        log.info("ProjectWakeupTick: orphans={} brought={} skipped={}",
                orphans.size(), won, skipped);
    }
}
