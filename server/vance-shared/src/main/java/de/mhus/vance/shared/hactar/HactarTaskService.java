package de.mhus.vance.shared.hactar;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.hactar.HactarTaskRunStatus;
import de.mhus.vance.api.hactar.HactarTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Owns data plane for {@code hactar_tasks}. All task lookups, inserts,
 * status flips and the atomic claim/release operations live here —
 * callers in {@code vance-brain} go through this service rather than
 * touching the repository directly (CLAUDE.md datahoheit rule).
 */
@Service
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class HactarTaskService {

    private final HactarTaskRepository repository;
    private final MongoTemplate mongoTemplate;

    public HactarTaskDocument insert(HactarTaskDocument task) {
        if (task.getCreatedAt() == null) {
            task.setCreatedAt(Instant.now());
        }
        if (task.getNextAttemptAt() == null) {
            task.setNextAttemptAt(task.getCreatedAt());
        }
        return repository.save(task);
    }

    public Optional<HactarTaskDocument> findById(String id) {
        return repository.findById(id);
    }

    /** Lookup helper for {@code HactarThinkProcessCompletionListener}. */
    public Optional<HactarTaskDocument> findBySubProcessId(String subProcessId) {
        return repository.findBySubProcessId(subProcessId);
    }

    /** Record the {@code subProcessId} on a CLAIMED task and mark it WAITING_SUBPROCESS. */
    public void linkSubProcess(String taskId, String subProcessId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("subProcessId", subProcessId)
                .set("runStatus", HactarTaskRunStatus.WAITING_SUBPROCESS);
        mongoTemplate.updateFirst(q, u, HactarTaskDocument.class);
    }

    /** Inbox-equivalent of {@link #linkSubProcess} — used by {@code GateTaskExecutor}. */
    public void linkInboxItem(String taskId, String inboxItemId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("inboxItemId", inboxItemId)
                .set("runStatus", HactarTaskRunStatus.WAITING_INBOX);
        mongoTemplate.updateFirst(q, u, HactarTaskDocument.class);
    }

    /** Lookup helper for {@code HactarInboxCompletionListener}. */
    public Optional<HactarTaskDocument> findByInboxItemId(String inboxItemId) {
        return repository.findByInboxItemId(inboxItemId);
    }

    /** Timer-equivalent of {@link #linkSubProcess} — used by {@code TimerTaskExecutor}. */
    public void linkTimer(String taskId, String timerId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("timerId", timerId)
                .set("runStatus", HactarTaskRunStatus.WAITING_TIMER);
        mongoTemplate.updateFirst(q, u, HactarTaskDocument.class);
    }

    /** Sub-workflow equivalent of {@link #linkSubProcess} — used by {@code WorkflowTaskExecutor}. */
    public void linkSubWorkflow(String taskId, String subWorkflowRunId) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("subWorkflowRunId", subWorkflowRunId)
                .set("runStatus", HactarTaskRunStatus.WAITING_SUBWORKFLOW);
        mongoTemplate.updateFirst(q, u, HactarTaskDocument.class);
    }

    /** Lookup helper for {@code HactarSubWorkflowCompletionListener}. */
    public Optional<HactarTaskDocument> findBySubWorkflowRunId(String subWorkflowRunId) {
        return repository.findBySubWorkflowRunId(subWorkflowRunId);
    }

    /**
     * Atomic CLAIMED → PENDING reclaim. Caller has already filtered by
     * staleness; the CAS predicates on status + version so two pods
     * racing on the same stale row produce one winner. The
     * {@code attemptCount} is incremented so a chronically-failing
     * task eventually trips {@link #failTerminally}.
     *
     * @return the post-CAS document if this caller won the race
     */
    public Optional<HactarTaskDocument> reclaim(String taskId) {
        HactarTaskDocument current = repository.findById(taskId).orElse(null);
        if (current == null) return Optional.empty();
        if (current.getStatus() != HactarTaskStatus.CLAIMED) return Optional.empty();

        Query q = new Query(Criteria.where("_id").is(taskId)
                .and("status").is(HactarTaskStatus.CLAIMED)
                .and("version").is(current.getVersion()));
        Update u = new Update()
                .set("status", HactarTaskStatus.PENDING)
                .set("nextAttemptAt", Instant.now())
                .unset("claimedBy")
                .unset("claimedAt")
                .unset("heartbeatAt")
                .unset("runStatus")
                .inc("version", 1L);
        com.mongodb.client.result.UpdateResult result =
                mongoTemplate.updateFirst(q, u, HactarTaskDocument.class);
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
        mongoTemplate.updateFirst(q, u, HactarTaskDocument.class);
    }

    public List<HactarTaskDocument> findByRun(String workflowRunId) {
        return repository.findByWorkflowRunId(workflowRunId);
    }

    /**
     * Atomic PENDING → CLAIMED transition. Returns the fresh document on
     * success, or {@link Optional#empty()} when another pod won the
     * race (optimistic-version-mismatch).
     */
    public Optional<HactarTaskDocument> claim(String taskId, String podId, Instant claimedAt) {
        HactarTaskDocument current = repository.findById(taskId).orElse(null);
        if (current == null) return Optional.empty();
        if (current.getStatus() != HactarTaskStatus.PENDING) return Optional.empty();

        Query q = new Query(Criteria.where("_id").is(taskId)
                .and("status").is(HactarTaskStatus.PENDING)
                .and("version").is(current.getVersion()));
        Update u = new Update()
                .set("status", HactarTaskStatus.CLAIMED)
                .set("claimedBy", podId)
                .set("claimedAt", claimedAt)
                .inc("version", 1L)
                .inc("attemptCount", 1);
        UpdateResult result = mongoTemplate.updateFirst(q, u, HactarTaskDocument.class);
        if (result.getModifiedCount() == 0) {
            return Optional.empty();
        }
        return repository.findById(taskId);
    }

    public void markDone(String taskId) {
        markTerminal(taskId, HactarTaskStatus.DONE);
    }

    public void markFailed(String taskId) {
        markTerminal(taskId, HactarTaskStatus.FAILED);
    }

    private void markTerminal(String taskId, HactarTaskStatus status) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update()
                .set("status", status)
                .unset("runStatus")
                .unset("heartbeatAt");
        mongoTemplate.updateFirst(q, u, HactarTaskDocument.class);
    }

    public void setRunStatus(String taskId, @Nullable HactarTaskRunStatus runStatus) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update();
        if (runStatus == null) {
            u.unset("runStatus");
        } else {
            u.set("runStatus", runStatus);
        }
        mongoTemplate.updateFirst(q, u, HactarTaskDocument.class);
    }

    public long deleteRun(String workflowRunId) {
        return repository.deleteByWorkflowRunId(workflowRunId);
    }
}
