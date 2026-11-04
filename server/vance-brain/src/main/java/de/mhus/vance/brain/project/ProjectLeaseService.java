package de.mhus.vance.brain.project;

import de.mhus.vance.api.projects.ProjectLiveState;
import de.mhus.vance.brain.cluster.ClusterService;
import de.mhus.vance.shared.megadodo.MegadodoService;
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
 * of projects this pod thinks it activated
 * ({@link ProjectActivationRegistry#size()}). Equal — the normal case — costs nothing
 * beyond the write. Short means at least one is gone, and only then do we pay
 * for a query to find out which.
 *
 * <p><b>Deactivation writes nothing.</b> We lost the lease, so the new owner
 * has already initialised the project; our local folder is a stale copy
 * that must not be treated as theirs. So the teardown is {@link ProjectEnginesStopRequested} only — every listener on
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
    private final ProjectLiveService projectLive;
    private final ProjectActivationRegistry activationRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final MegadodoService megadodoService;

    @Scheduled(
            fixedDelayString = "${vance.cluster.lease.renew-interval:PT1M}",
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
        renewLiveState();
        int expected = activationRegistry.size();
        if (held >= expected) {
            log.trace(
                    "ProjectLeaseService: renewed {} lease(s), {} lease-holding project(s) " + "activated",
                    held,
                    expected);
            return;
        }
        log.warn(
                "ProjectLeaseService: hold {} lease(s) but {} lease-holding project(s) are "
                        + "activated here — reconciling",
                held,
                expected);
        deactivateLostProjects(selfPodId);
    }

    /** Keeps the live entries of the projects running here as long-lived as their leases. */
    private void renewLiveState() {
        forEachActivated((tenantId, projectName) -> {
            if (!projectLive.renew(tenantId, projectName)) {
                // The entry ran out or was lost: write it again from the document, so the
                // feed does not stay silent until the next state change.
                projectService
                        .findByTenantAndName(tenantId, projectName)
                        .ifPresent(doc -> projectLive.recordActual(tenantId, projectName, doc.getActualState()));
            }
        });
    }

    private void forEachActivated(java.util.function.BiConsumer<String, String> action) {
        for (String key : activationRegistry.snapshot()) {
            int slash = key.indexOf('/');
            if (slash <= 0) continue;
            action.accept(key.substring(0, slash), key.substring(slash + 1));
        }
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
            if (activationRegistry.deactivate(tenantId, projectName)) {
                log.warn(
                        "Project '{}/{}' lease lost while active here — unloading local state "
                                + "(the new owner is authoritative)",
                        tenantId,
                        projectName);
                // The one involuntary departure that has a witness. A pod
                // killed outright never gets here — but a pod that is merely
                // *late* does, and without this row its projects would appear
                // to have left cleanly or not at all.
                megadodoService.projectHomeLost(
                        tenantId, projectName, clusterService.selfNodeName(), selfPodId, clusterService.selfEndpoint());
                eventPublisher.publishEvent(new ProjectEnginesStopRequested(tenantId, projectName));
            }
        }
    }

    /**
     * Unloads every project this pod has up before its leases are dropped, so
     * the projects go down in an orderly way instead of vanishing with the JVM.
     *
     * <p>Only the <em>actual</em> state is touched, and that happens in
     * {@code releaseLeases} (it writes STOPPED together with the lease). The
     * desired state stays as it is: a restart is not a decision about the
     * project, and whichever pod takes it over next brings it back.
     */
    private void unloadActivatedProjects() {
        for (String key : activationRegistry.snapshot()) {
            int slash = key.indexOf('/');
            if (slash < 0) continue;
            String tenantId = key.substring(0, slash);
            String projectName = key.substring(slash + 1);
            try {
                eventPublisher.publishEvent(new ProjectEnginesStopRequested(tenantId, projectName));
                activationRegistry.deactivate(tenantId, projectName);
            } catch (RuntimeException e) {
                log.warn("ProjectLeaseService: unloading '{}' on shutdown failed: {}", key, e.toString());
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
        java.util.Set<String> activated = activationRegistry.snapshot();
        unloadActivatedProjects();
        try {
            long released = projectService.releaseLeases(
                    clusterService.selfPodId(), clusterService.selfNodeName(), clusterService.selfEndpoint());
            if (released > 0) {
                log.info("ProjectLeaseService: released {} project lease(s) on shutdown", released);
            }
            for (String key : activated) {
                int slash = key.indexOf('/');
                if (slash > 0) {
                    projectLive.record(key.substring(0, slash), key.substring(slash + 1), ProjectLiveState.STOPPED);
                }
            }
        } catch (RuntimeException e) {
            log.warn(
                    "ProjectLeaseService: lease release on shutdown failed " + "(leases will expire on their own): {}",
                    e.toString());
        }
    }
}
