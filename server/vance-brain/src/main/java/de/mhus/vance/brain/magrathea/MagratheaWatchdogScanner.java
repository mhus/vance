package de.mhus.vance.brain.magrathea;

import de.mhus.vance.brain.cluster.ClusterMasterService;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaStateSpec;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import de.mhus.vance.shared.magrathea.journal.StartRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The last net: a run whose task has not moved for
 * {@link MagratheaProperties#getStallCeiling()} is failed, whatever the
 * reason.
 *
 * <p>Every other recovery here answers a <em>named</em> failure — the
 * reclaim scanner answers "the pod died", the subprocess reconciler
 * answers "the completion event was lost", the timeout scheduler answers
 * "the thing we handed control to never returned". Those cover the
 * failures that have been seen. This one exists because the list of
 * failures that can be seen is not finite: a defect in a type-executor,
 * a wedged lane, a timer that failed to insert, a {@code catch:} that
 * routes back into the same hang. Each would leave a run alive and
 * motionless forever, and each is a different bug.
 *
 * <p>So the trigger is deliberately not diagnostic. It asks one question
 * — has this task been sitting in a non-terminal state longer than any
 * legitimate work would — and needs no theory about why.
 *
 * <p>The ceiling is far above the type deadlines
 * ({@code defaultAgentTimeout} and friends) on purpose. Those are the
 * recoverable net and should fire first, routing through
 * {@code on:}/{@code catch:} so the workflow can react. Reaching the
 * watchdog means that net failed too, so its verdict is terminal:
 * {@code FAILED}, with the unwind a stop would do — agents closed,
 * gates dismissed, sub-runs stopped.
 *
 * <p><b>Master-only.</b> Unlike its neighbours in this package, this scan
 * runs on one pod. The claimer and the reclaim scanner are pod-local
 * because every step they take is a Mongo CAS, so a race just produces
 * one winner; this one ends a whole run through a chain of side effects
 * — closing processes, dismissing inbox items, stopping sub-runs,
 * appending a terminal record — with no compare-and-set anywhere in it.
 * Two pods arriving together would unwind the same run twice and journal
 * two terminal records. So it takes the master lease like the other
 * cluster-wide sweeps ({@code ClusterCleanupTick} and friends). Without
 * a master service the pod is alone and runs it.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaWatchdogScanner {

    private static final int SCAN_BATCH = 64;

    private final MagratheaTaskService taskService;
    private final MagratheaWorkflowService workflowService;
    private final MagratheaProperties properties;
    private final MagratheaJournalService journalService;
    private final MagratheaWorkflowLoader workflowLoader;
    /** Absent when the cluster-master feature is off — then this pod is alone. */
    private final ObjectProvider<ClusterMasterService> masterServiceProvider;

    /**
     * The cadence is unrelated to the ceiling: it decides how late a
     * verdict may be, not when it is due. Against a fourteen-day ceiling
     * an hour of lateness is nothing, so an hour is the cadence — a run
     * that has stood for two weeks is not more urgent for having stood
     * two weeks and five minutes.
     */
    @Scheduled(
            fixedDelayString = "${vance.magrathea.watchdog-interval:PT1H}",
            initialDelayString = "${vance.magrathea.watchdog-interval:PT1H}")
    public void scan() {
        if (!isResponsiblePod()) return;

        Duration ceiling = properties.getStallCeiling();
        if (ceiling == null || ceiling.isZero() || ceiling.isNegative()) return;

        Instant threshold = Instant.now().minus(ceiling);
        List<MagratheaTaskDocument> stalled = taskService.findStalledBefore(threshold, SCAN_BATCH);
        if (stalled.isEmpty()) return;

        // One run can hold several stalled tasks; failing it once is enough,
        // and the second call would be a no-op anyway (endRun rejects a
        // terminal run) — this just keeps the log honest.
        Set<String> seen = new LinkedHashSet<>();
        for (MagratheaTaskDocument task : stalled) {
            String runId = task.getWorkflowRunId();
            if (runId == null) continue;
            if (waitsForever(task)) {
                log.debug("Magrathea watchdog: run {} state '{}' declared timeoutSeconds: 0 "
                        + "— left standing", runId, task.getStateName());
                continue;
            }
            if (!seen.add(runId)) continue;
            String reason = "watchdog: no progress for " + ceiling
                    + " (task '" + task.getStateName() + "' " + task.getStatus() + ")";
            try {
                if (workflowService.failStalledRun(
                        task.getTenantId(), task.getProjectId(), runId, reason)) {
                    log.warn("Magrathea watchdog: run {} failed — {}", runId, reason);
                }
            } catch (RuntimeException ex) {
                // A wedged lane is one of the things this scanner is for, so
                // it must not take the scan down with it — the next pass
                // tries again.
                log.warn("Magrathea watchdog: could not fail stalled run {}: {}",
                        runId, ex.toString());
            }
        }
    }

    /**
     * True when the task's frozen state declared {@code timeoutSeconds: 0} —
     * the one way an author says "this one really may wait forever".
     *
     * <p>The deadline scheduler already honours that zero; without the same
     * exemption here the two nets contradicted each other, and the watchdog
     * won: a gate deliberately left open until somebody comes was failed
     * after the ceiling with the reason "no progress", i.e. reported as a
     * defect when it was the written intent. Cost is one journal read plus
     * one YAML parse per candidate, on an hourly master-only scan of at most
     * {@value #SCAN_BATCH} rows.
     *
     * <p>An unreadable plan is <em>not</em> treated as an opt-out: a run
     * whose frozen definition cannot be parsed is exactly the kind of defect
     * this net exists for, and inventing a waiver from a failure would make
     * the last net disappear where it is needed most.
     */
    private boolean waitsForever(MagratheaTaskDocument task) {
        try {
            StartRecord start = journalService.readLast(
                    task.getTenantId(), task.getProjectId(),
                    task.getWorkflowRunId(), StartRecord.class).orElse(null);
            if (start == null) return false;
            MagratheaStateSpec state = workflowLoader
                    .validateYaml(start.getWorkflowName(), start.getDefinitionYaml())
                    .states()
                    .get(task.getStateName());
            return state != null
                    && state.timeoutSeconds() != null
                    && state.timeoutSeconds() <= 0;
        } catch (RuntimeException ex) {
            log.debug("Magrathea watchdog: could not read the frozen plan of run {}: {}",
                    task.getWorkflowRunId(), ex.toString());
            return false;
        }
    }

    /**
     * One pod sweeps. A missing {@link ClusterMasterService} means the
     * feature is switched off, which only happens in a single-pod
     * deployment — refusing to sweep there would disable the net exactly
     * where nobody else can take over.
     */
    private boolean isResponsiblePod() {
        ClusterMasterService masterService = masterServiceProvider.getIfAvailable();
        return masterService == null || masterService.isLocalPodMaster();
    }
}
