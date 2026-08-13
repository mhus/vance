package de.mhus.vance.brain.magrathea;

import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaWatchdogScanner {

    private static final long SCAN_INTERVAL_MS = 300_000L;
    private static final int SCAN_BATCH = 64;

    private final MagratheaTaskService taskService;
    private final MagratheaWorkflowService workflowService;
    private final MagratheaProperties properties;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = SCAN_INTERVAL_MS)
    public void scan() {
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
            if (runId == null || !seen.add(runId)) continue;
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
}
