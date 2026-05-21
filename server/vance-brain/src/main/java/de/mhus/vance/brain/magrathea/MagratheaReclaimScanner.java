package de.mhus.vance.brain.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import de.mhus.vance.shared.magrathea.MagratheaTaskService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pod-local scheduled scanner that frees orphaned CLAIMED tasks
 * (plan §11). A "stale" task is one with
 * {@code status == CLAIMED AND runStatus IS NULL} (i.e. a synchronous
 * type-executor that lost its pod mid-run) whose
 * {@code claimedAt < now - graceTimeout} and whose {@code heartbeatAt}
 * is either unset or also stale.
 *
 * <p>Tasks in {@code WAITING_SUBPROCESS} / {@code WAITING_INBOX} /
 * {@code WAITING_TIMER} / {@code WAITING_SUBWORKFLOW} are
 * <strong>not</strong> reclaimed — their completion arrives through
 * the matching listener regardless of which pod claimed them.
 *
 * <p>When {@code attemptCount > maxClaimAttempts}, the task is failed
 * terminally with {@code outcome = technical_error}; the
 * state-machine's {@code catch:} block handles recovery from there.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaReclaimScanner {

    private static final long SCAN_INTERVAL_MS = 60_000L;
    private static final int SCAN_BATCH = 64;
    private static final Duration GRACE_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final MongoTemplate mongoTemplate;
    private final MagratheaTaskService taskService;
    private final MagratheaCompletionEventBus eventBus;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = SCAN_INTERVAL_MS)
    public void scan() {
        Instant threshold = Instant.now().minus(GRACE_TIMEOUT);
        Query q = new Query(
                Criteria.where("status").is(MagratheaTaskStatus.CLAIMED)
                        .and("runStatus").is(null)
                        .and("claimedAt").lt(threshold)
                        .orOperator(
                                Criteria.where("heartbeatAt").is(null),
                                Criteria.where("heartbeatAt").lt(threshold)))
                .limit(SCAN_BATCH);
        List<MagratheaTaskDocument> stale = mongoTemplate.find(q, MagratheaTaskDocument.class);
        if (stale.isEmpty()) return;
        for (MagratheaTaskDocument task : stale) {
            if (task.getAttemptCount() >= MAX_CLAIM_ATTEMPTS) {
                failTerminally(task);
            } else {
                reclaim(task);
            }
        }
    }

    private void reclaim(MagratheaTaskDocument task) {
        Optional<MagratheaTaskDocument> reclaimed = taskService.reclaim(task.getId());
        if (reclaimed.isEmpty()) {
            log.debug("Magrathea reclaim race lost for task {}", task.getId());
            return;
        }
        log.warn("Magrathea reclaim: task='{}' state='{}' attemptCount={} re-queued",
                task.getId(), task.getStateName(), reclaimed.get().getAttemptCount());
    }

    private void failTerminally(MagratheaTaskDocument task) {
        // The completion-event flow handles the actual termination —
        // we publish the same shape the type-executor would have
        // produced on its own failure. The dispatcher's idempotency
        // (appendIfAbsent on TaskResultRecord) keeps duplicates safe.
        log.warn("Magrathea reclaim: task='{}' state='{}' attemptCount={} exhausted — failing",
                task.getId(), task.getStateName(), task.getAttemptCount());
        eventBus.publish(new TaskCompletedEvent(
                task.getTenantId(),
                task.getProjectId(),
                task.getWorkflowRunId(),
                task.getId(),
                task.getStateName(),
                task.getTaskType() == null ? MagratheaTaskType.CONDITION_TASK : task.getTaskType(),
                "technical_error",
                /* output */ null,
                "Magrathea task exhausted max claim attempts ("
                        + MAX_CLAIM_ATTEMPTS + ") — last pod=" + task.getClaimedBy(),
                /* durationMs */ 0L,
                /* nextStateOverride */ null));
    }
}
