package de.mhus.vance.shared.magrathea;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.magrathea.MagratheaTaskRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
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
     * racing on the same stale row produce one winner. The
     * {@code attemptCount} is incremented so a chronically-failing
     * task eventually trips {@link #failTerminally}.
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

    public void setRunStatus(String taskId, @Nullable MagratheaTaskRunStatus runStatus) {
        Query q = new Query(Criteria.where("_id").is(taskId));
        Update u = new Update();
        if (runStatus == null) {
            u.unset("runStatus");
        } else {
            u.set("runStatus", runStatus);
        }
        mongoTemplate.updateFirst(q, u, MagratheaTaskDocument.class);
    }

    public long deleteRun(String workflowRunId) {
        return repository.deleteByWorkflowRunId(workflowRunId);
    }
}
