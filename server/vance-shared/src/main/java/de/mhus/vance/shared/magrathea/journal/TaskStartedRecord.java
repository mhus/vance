package de.mhus.vance.shared.magrathea.journal;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Type-executor has begun working on a task. Written before any
 * external effect — together with {@link TaskResultRecord} it brackets
 * the task's execution window for replay and audit (plan §4.0).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStartedRecord implements JournalRecord {

    private String state;
    private MagratheaTaskType taskType;

    /** Mongo {@code _id} of the {@code magrathea_tasks} row. */
    private String taskId;

    /** Pod that claimed and started this task. */
    private @Nullable String claimedBy;

    /** Spawned {@code ThinkProcess} id for {@code agent_task}. */
    private @Nullable String subProcessId;

    /** Spawned sub-{@code MagratheaProcess} run id for {@code workflow_task}. */
    private @Nullable String subWorkflowRunId;
}
