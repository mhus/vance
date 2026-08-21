package de.mhus.vance.brain.project;

import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps this pod's project leases alive, and notices when one was taken away.
 *
 * <p>Renewal is a single {@code updateMulti} per beat — "refresh everything I
 * hold" — so the cost does not grow with the number of tenants and projects.
 * That is the reason ownership is keyed on a pod id with an index behind it
 * rather than on a node name joined against the pod registry.
 *
 * <h2>Drift</h2>
 * A lease can be lost while the pod is still running: a long GC pause, a Mongo
 * hiccup, a paused JVM under a debugger — the holder stops renewing, the lease
 * expires, and another pod legitimately takes the project over. Until now
 * nothing noticed. The first pod kept its scheduler, hooks and tool scopes
 * loaded for a project it no longer owned, indefinitely: wasted memory on
 * every pod that ever touched a project, and two pods with in-memory state for
 * the same one.
 *
 * <p>Detection is free: compare the renewal's matched count against the number
 * of projects this pod thinks it activated. Equal — the normal case — costs
 * nothing beyond the write. Short means at least one is gone, and only then do
 * we pay for a query to find out which.
 *
 * <p><b>Deactivation writes nothing.</b> We lost the lease, so the new owner
 * has already initialised the workspace from Mongo; our local folder is a
 * stale copy and snapshotting it back would overwrite their state. So the
 * teardown is {@link ProjectEnginesStopRequested} only — every listener on
 * that event is a pure in-memory unload — and the folder is left for the next
 * init or the orphan-storage sweep.
 *
 * <p>Design: {@code planning/project-ownership-lease-design.md} §3 and §4.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectLeaseService {

    private final ProjectService projectService;
    private final ClusterService clusterService;
    private final ProjectActivationRegistry activationRegistry;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelayString = "${vance.cluster.lease.renew-interval:PT1M}",
            initialDelayString = "${vance.cluster.lease.renew-interval:PT1M}")
    public void tick() {
        try {
            renewAndReconcile();
        } catch (RuntimeException e) {
            // A failed beat is survivable: the lease is still valid for the
            // rest of its TTL, so the next tick has room to recover. Losing
            // projects because of one Mongo blip would be the worse outcome.
            log.warn("ProjectLeaseService: renewal round failed: {}", e.toString());
        }
    }

    /** Extracted so tests can drive a round deterministically. */
    void renewAndReconcile() {
        String selfPodId = clusterService.selfPodId();
        long held = projectService.renewLeases(selfPodId, Instant.now());
        int activated = activationRegistry.size();
        if (held >= activated) {
            log.trace("ProjectLeaseService: renewed {} lease(s), {} activated", held, activated);
            return;
        }
        log.warn("ProjectLeaseService: hold {} lease(s) but {} project(s) are activated here — "
                + "reconciling", held, activated);
        deactivateLostProjects(selfPodId);
    }

    /**
     * Drops local state for every activated project whose lease is no longer
     * ours. Only reached when the counts disagree, so the extra query is paid
     * for by an actual anomaly.
     */
    private void deactivateLostProjects(String selfPodId) {
        List<ProjectDocument> stillOurs = projectService.findByHomePodId(selfPodId);
        Set<String> ourKeys = stillOurs.stream()
                .map(p -> ProjectActivationRegistry.key(p.getTenantId(), p.getName()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        for (String key : activationRegistry.snapshot()) {
            if (ourKeys.contains(key)) continue;
            int slash = key.indexOf('/');
            if (slash <= 0) continue;
            String tenantId = key.substring(0, slash);
            String projectName = key.substring(slash + 1);
            if (ProjectService.isPodless(projectName)) {
                // Podless projects never take a lease, so they can never show
                // up as "lost" — they are simply not part of this accounting.
                continue;
            }
            if (activationRegistry.deactivate(tenantId, projectName)) {
                log.warn("Project '{}/{}' lease lost while active here — unloading local state "
                        + "(no workspace snapshot: the new owner is authoritative)",
                        tenantId, projectName);
                eventPublisher.publishEvent(
                        new ProjectEnginesStopRequested(tenantId, projectName));
            }
        }
    }

    /**
     * Clean-shutdown courtesy: drop our leases so the next pod can take the
     * projects over immediately instead of waiting out the TTL.
     *
     * <p>Best-effort on purpose. Correctness must not depend on a shutdown
     * hook — {@code kill -9}, OOM and pod eviction run none — and with an
     * expiring lease it does not: this only shortens the handover.
     */
    @PreDestroy
    void releaseOnShutdown() {
        try {
            long released = projectService.releaseLeases(clusterService.selfPodId());
            if (released > 0) {
                log.info("ProjectLeaseService: released {} project lease(s) on shutdown",
                        released);
            }
        } catch (RuntimeException e) {
            log.warn("ProjectLeaseService: lease release on shutdown failed "
                    + "(leases will expire on their own): {}", e.toString());
        }
    }
}
