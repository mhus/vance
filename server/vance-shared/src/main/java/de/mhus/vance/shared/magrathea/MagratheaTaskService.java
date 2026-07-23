package de.mhus.vance.shared.magrathea;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.magrathea.MagratheaTaskRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Owns data plane for {@code magrathea_tasks}. All task lookups, inserts,
 * status flips and the atomic claim/release operations live here —
 * callers in {@code vance-brain} go through this service rather than
 * touching the repository directly (CLAUDE.md datahoheit rule).
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class MagratheaTaskService {

    private final MagratheaTaskRepository repository;
    private final MongoTemplate mongoTemplate;

    public MagratheaTaskDocument insert(MagratheaTaskDocument task) {
        if (task.getCreatedAt() == null) {
            task.setCreatedAt(Instant.now());
        }
        if (task.getNextAttemptAt() == null) {
            task.setNextAttemptAt(task.getCreatedAt());
        }
        return repository.save(task);
    }

    public Optional<MagratheaTaskDocument> findById(String id) {
        return repository.findById(id);
    }

    /** Lookup helper for {@code MagratheaThinkProcessCompletionListener}. */
    public Optional<MagratheaTaskDocument> findBySubProcessId(String subProcessId) {
        return repository.findBySubProcessId(subProcessId);
    }

    /** Record the {@code subProcessId} on a CLAIMED task and mark it WAITING_SUBPROCESS. */
    public void linkSubProcess(String taskId, String subProcessId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("subProcessId", subProcessId)
                .set("runStatus", MagratheaTaskRunStatus.WAITING_SUBPROCESS);
        mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
    }

    /**
     * Reverse of {@link #linkSubProcess}: clears the sub-process link and
     * the WAITING_SUBPROCESS run-status. Used when spawning failed after
     * the link but before the process could start, so the completion
     * listener won't later match the abandoned process to this task.
     */
    public void unlinkSubProcess(String taskId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update().unset("subProcessId").unset("runStatus");
        mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
    }

    /** Inbox-equivalent of {@link #linkSubProcess} — used by {@code GateTaskExecutor}. */
    public void linkInboxItem(String taskId, String inboxItemId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("inboxItemId", inboxItemId)
                .set("runStatus", MagratheaTaskRunStatus.WAITING_INBOX);
        mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
    }

    /** Lookup helper for {@code MagratheaInboxCompletionListener}. */
    public Optional<MagratheaTaskDocument> findByInboxItemId(String inboxItemId) {
        return repository.findByInboxItemId(inboxItemId);
    }

    /** Timer-equivalent of {@link #linkSubProcess} — used by {@code TimerTaskExecutor}. */
    public void linkTimer(String taskId, String timerId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("timerId", timerId)
                .set("runStatus", MagratheaTaskRunStatus.WAITING_TIMER);
        mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
    }

    /** Sub-workflow equivalent of {@link #linkSubProcess} — used by {@code WorkflowTaskExecutor}. */
    public void linkSubWorkflow(String taskId, String subWorkflowRunId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("subWorkflowRunId", subWorkflowRunId)
                .set("runStatus", MagratheaTaskRunStatus.WAITING_SUBWORKFLOW);
        mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
    }

    /** Lookup helper for {@code MagratheaSubWorkflowCompletionListener}. */
    public Optional<MagratheaTaskDocument> findBySubWorkflowRunId(String subWorkflowRunId) {
        return repository.findBySubWorkflowRunId(subWorkflowRunId);
    }

    /**
     * Atomic CLAIMED → PENDING reclaim. Caller has already filtered by
     * staleness; the CAS predicates on status + version so two pods
     * racing on the same stale row produce one winner. Reclaim itself
     * does <em>not</em> touch {@code attemptCount} — the following
     * {@link #claim(String, String, Instant)} increments it, so a
     * chronically-failing task still trips the scanner's max-attempts
     * cut-off after enough reclaim→claim cycles.
     *
     * @return the post-CAS document if this caller won the race
     */
    public Optional<MagratheaTaskDocument> reclaim(String taskId) {
        MagratheaTaskDocument current = repository.findById(taskId).orElse(null);
        if (current == null) return Optional.empty();
        if (current.getStatus() != MagratheaTaskStatus.CLAIMED) return Optional.empty();

        Query q = new Query(Criteria.where("_id").is(taskId)
                .and("status").is(MagratheaTaskStatus.CLAIMED)
                .and("version").is(current.getVersion()));
        Update u = new Update()
                .set("status", MagratheaTaskStatus.PENDING)
                .set("nextAttemptAt", Instant.now())
                .unset("claimedBy")
                .unset("claimedAt")
                .unset("heartbeatAt")
                .unset("runStatus")
                .inc("version", 1L);
        com.mongodb.client.result.UpdateResult result =
                mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
        if (result.getModifiedCount() == 0) return Optional.empty();
        return repository.findById(taskId);
    }

    /**
     * Heartbeat ping for long-running synchronous executors —
     * refreshes the document's {@code heartbeatAt} so the reclaim
     * scanner doesn't treat the task as orphaned.
     */
    public void touchHeartbeat(String taskId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update().set("heartbeatAt", Instant.now());
        mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
    }

    public List<MagratheaTaskDocument> findByRun(String workflowRunId) {
        return repository.findByWorkflowRunId(workflowRunId);
    }

    /**
     * Claimer helper: PENDING tasks whose {@code nextAttemptAt} is due,
     * oldest-due first, capped at {@code limit}. Keeps the claim scan
     * behind the owning service instead of a raw collection query.
     */
    public List<MagratheaTaskDocument> findClaimable(Instant now, int limit) {
        Query q = new Query(
                Criteria.where("status").is(MagratheaTaskStatus.PENDING)
                        .and("nextAttemptAt").lte(now))
                .with(org.springframework.data.domain.Sort.by("nextAttemptAt").ascending())
                .limit(limit);
        return mongoTemplate.find(q, MagratheaTaskDocument.class);
    }

    /**
     * Reclaim-scanner helper: CLAIMED synchronous tasks
     * ({@code runStatus == null}) whose claim is older than the grace and
     * whose heartbeat is unset or also stale — the orphaned-mid-run set.
     */
    public List<MagratheaTaskDocument> findStaleClaimed(Instant threshold, int limit) {
        Query q = new Query(
                Criteria.where("status").is(MagratheaTaskStatus.CLAIMED)
                        .and("runStatus").is(null)
                        .and("claimedAt").lt(threshold)
                        .orOperator(
                                Criteria.where("heartbeatAt").is(null),
                                Criteria.where("heartbeatAt").lt(threshold)))
                .limit(limit);
        return mongoTemplate.find(q, MagratheaTaskDocument.class);
    }

    /**
     * Crash-recovery helper: CLAIMED tasks still parked in
     * {@code WAITING_SUBPROCESS} whose claim is older than the grace.
     * A task waiting this long after its subprocess closed lost its
     * in-memory completion event (pod crash); the recovery scanner
     * reconciles it against the actual ThinkProcess status.
     */
    public List<MagratheaTaskDocument> findWaitingSubprocessClaimedBefore(
            Instant threshold, int limit) {
        Query q = new Query(
                Criteria.where("status").is(MagratheaTaskStatus.CLAIMED)
                        .and("runStatus").is(MagratheaTaskRunStatus.WAITING_SUBPROCESS)
                        .and("claimedAt").lt(threshold))
                .limit(limit);
        return mongoTemplate.find(q, MagratheaTaskDocument.class);
    }

    /**
     * Atomic PENDING → CLAIMED transition. Returns the fresh document on
     * success, or {@link Optional#empty()} when another pod won the
     * race (optimistic-version-mismatch).
     */
    public Optional<MagratheaTaskDocument> claim(String taskId, String podId, Instant claimedAt) {
        MagratheaTaskDocument current = repository.findById(taskId).orElse(null);
        if (current == null) return Optional.empty();
        if (current.getStatus() != MagratheaTaskStatus.PENDING) return Optional.empty();

        Query q = new Query(Criteria.where("_id").is(taskId)
                .and("status").is(MagratheaTaskStatus.PENDING)
                .and("version").is(current.getVersion()));
        Update u = new Update()
                .set("status", MagratheaTaskStatus.CLAIMED)
                .set("claimedBy", podId)
                .set("claimedAt", claimedAt)
                .inc("version", 1L)
                .inc("attemptCount", 1);
        UpdateResult result = mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
        if (result.getModifiedCount() == 0) {
            return Optional.empty();
        }
        return repository.findById(taskId);
    }

    public void markDone(String taskId) {
        markTerminal(taskId, MagratheaTaskStatus.DONE);
    }

    public void markFailed(String taskId) {
        markTerminal(taskId, MagratheaTaskStatus.FAILED);
    }

    private void markTerminal(String taskId, MagratheaTaskStatus status) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("status", status)
                .unset("runStatus")
                .unset("heartbeatAt");
        mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
    }

    public long deleteRun(String workflowRunId) {
        return repository.deleteByWorkflowRunId(workflowRunId);
    }
}
