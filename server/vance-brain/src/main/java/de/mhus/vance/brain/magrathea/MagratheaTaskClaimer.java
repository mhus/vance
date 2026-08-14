package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pod-local scheduled scanner. Every {@value #SCAN_INTERVAL_MS}ms it
 * scans {@code magrathea_tasks} for {@link MagratheaTaskStatus#PENDING} rows
 * whose {@code nextAttemptAt} is due, atomically flips them to
 * {@link MagratheaTaskStatus#CLAIMED} via Mongo {@code findAndModify}, and
 * dispatches each claimed task to the matching {@link MagratheaProjectLane}
 * for execution (plan §6.2).
 *
 * <p>Optimistic version is enforced via the {@code @Version} field of
 * {@link MagratheaTaskDocument}: the CAS update predicates on the current
 * version, so two pods racing on the same row produce exactly one
 * winner.
 *
 * <p><b>This is the recovery path, not the drive train.</b> In the ordinary
 * case a task is claimed the moment it is written, by
 * {@link MagratheaLocalDispatch} on the pod that wrote it. What reaches this
 * scanner is what that missed: rows from a pod that died, runs that were
 * released from a hold, retries whose back-off has now elapsed, and anything
 * the fast path declined to take. Every one of those is a case where nobody
 * else is coming.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaTaskClaimer {

    private static final long SCAN_INTERVAL_MS = 2_000L;
    private static final int CLAIM_BATCH = 32;

    private final MagratheaTaskService taskService;
    private final MagratheaProjectLaneManager laneManager;
    private final MagratheaTaskExecutor taskExecutor;
    private final MagratheaPodIdentity podIdentity;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = SCAN_INTERVAL_MS)
    public void scan() {
        List<MagratheaTaskDocument> tasks = taskService.findClaimable(Instant.now(), CLAIM_BATCH);
        if (tasks.isEmpty()) return;

        String podId = podIdentity.podId();
        Instant claimedAt = Instant.now();
        for (MagratheaTaskDocument task : tasks) {
            Optional<MagratheaTaskDocument> claimed = taskService.claim(task.getId(), podId, claimedAt);
            if (claimed.isEmpty()) {
                log.debug("Magrathea task {} claim lost to another pod", task.getId());
                continue;
            }
            MagratheaTaskDocument claimedTask = claimed.get();
            laneManager.submit(claimedTask.getProjectId(),
                    () -> taskExecutor.execute(claimedTask));
        }
    }
}
