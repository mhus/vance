package de.mhus.vance.brain.tools.exec;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic, <em>pod-local</em> sweep that reconciles exec jobs stuck in
 * {@code RUNNING} because their worker thread died without running its
 * {@code finally} (see {@link ExecManager#reconcileOrphanedJobs}).
 *
 * <p>Runs on <b>every</b> pod and is deliberately <b>not</b> cluster-master
 * gated — unlike the Mongo-writing cluster ticks (e.g. {@code
 * SessionStaleBindSweepTick}). {@link ExecManager}'s job map, the {@code
 * ExecutionRegistryService}, and the {@code Process} liveness handle are all
 * in-memory per pod, so each pod must clean up its own jobs; master-gating
 * would leave every non-master pod's orphans stuck forever.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecOrphanSweepTick {

    private final ExecManager execManager;

    @Scheduled(fixedDelayString = "${vance.exec.orphanSweep.interval:PT60S}",
            initialDelayString = "${vance.exec.orphanSweep.initialDelay:PT2M}")
    public void tick() {
        try {
            int reconciled = execManager.reconcileOrphanedJobs(Instant.now());
            if (reconciled > 0) {
                log.info("ExecOrphanSweepTick: reconciled {} orphaned exec job(s)", reconciled);
            }
        } catch (RuntimeException e) {
            log.warn("ExecOrphanSweepTick: sweep failed: {}", e.toString());
        }
    }
}
