package de.mhus.vance.shared.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskRunStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskStatus;
import de.mhus.vance.api.magrathea.MagratheaTaskType;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Open-task-list row in {@code magrathea_tasks}. One row per task that is
 * either waiting for a pod to claim ({@link MagratheaTaskStatus#PENDING})
 * or already CLAIMED and executing/waiting. Terminal rows
 * ({@code DONE}/{@code FAILED}) stay around briefly for audit before
 * the cleanup scan removes them — keeping them visible in queries
 * helps tooling.
 *
 * <p>See plan §3.3 for the lifecycle and §6.2 for the claim mechanic.
 */
@Document(collection = "magrathea_tasks")
@CompoundIndexes({
        @CompoundIndex(
                name = "claim_idx",
                def = "{ 'projectId': 1, 'status': 1, 'nextAttemptAt': 1 }"),
        @CompoundIndex(
                name = "reclaim_idx",
                def = "{ 'status': 1, 'claimedBy': 1, 'claimedAt': 1 }"),
        @CompoundIndex(
                name = "run_state_idx",
                def = "{ 'workflowRunId': 1, 'stateName': 1 }"),
        @CompoundIndex(
                name = "subprocess_idx",
                def = "{ 'subProcessId': 1 }",
                sparse = true),
        @CompoundIndex(
                name = "subworkflow_idx",
                def = "{ 'subWorkflowRunId': 1 }",
                sparse = true),
        @CompoundIndex(
                name = "inbox_idx",
                def = "{ 'inboxItemId': 1 }",
                sparse = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MagratheaTaskDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";
    private String projectId = "";
    private String workflowRunId = "";

    /** Workflow definition name — audit marker, makes the queue inspectable. */
    private String workflowName = "";

    /** State name from the YAML this task executes. */
    private String stateName = "";

    private MagratheaTaskType taskType = MagratheaTaskType.CONDITION_TASK;

    private MagratheaTaskStatus status = MagratheaTaskStatus.PENDING;

    /** Sub-state of CLAIMED tasks. {@code null} when type-executor is RUNNING in-process. */
    private @Nullable MagratheaTaskRunStatus runStatus;

    private @Nullable Instant createdAt;

    /**
     * When the next claim attempt should be made. For freshly inserted
     * tasks: equal to {@code createdAt}. For retries with backoff:
     * shifted into the future.
     */
    private @Nullable Instant nextAttemptAt;

    private int attemptCount;

    /**
     * State-level retry counter — incremented when the workflow's
     * {@code retry:} block re-enqueues this state after a matching
     * error-kind outcome. Distinct from {@link #attemptCount} which
     * tracks claim attempts (pod-crash reclaims).
     */
    private int retryCount;

    /** Pod that holds the current claim. */
    private @Nullable String claimedBy;

    private @Nullable Instant claimedAt;

    /** Set periodically by long-running type-executors; reclaim-scanner respects this. */
    private @Nullable Instant heartbeatAt;

    // ─── Sub-execution links (sparse) ───

    /** Spawned ThinkProcess for {@code agent_task} — drives reclaim and listener routing. */
    private @Nullable String subProcessId;

    /** Spawned sub-{@code MagratheaProcess} run id for {@code workflow_task}. */
    private @Nullable String subWorkflowRunId;

    /** Linked InboxItem for {@code gate_task}. */
    private @Nullable String inboxItemId;

    /** Linked timer doc for {@code timer_task}. */
    private @Nullable String timerId;

    @Version
    private @Nullable Long version;
}
