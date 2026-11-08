package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One workflow task execution in a project: what ran, on which pod, when and
 * for how long. Read from the shared run journal, so it covers every pod.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class WorkflowTaskRunInsightsDto {

    /** Run the task belongs to. */
    private String workflowRunId;

    /** Workflow name; null when the run's start record is no longer within the lookup window. */
    private @Nullable String workflowName;

    /** State the task executes. */
    private String state;

    /** Task type, e.g. {@code AGENT_TASK}. */
    private String taskType;

    /** Pod that claimed the task. */
    private @Nullable String podId;

    private @Nullable Instant startedAt;

    /** When the result was written; null while the task has no result. */
    private @Nullable Instant finishedAt;

    /** Wall-clock milliseconds between start and result; null while running. */
    private @Nullable Long durationMs;

    /**
     * The task's outcome ({@code success}, {@code failure}, a custom outcome or an error kind),
     * {@code running} while it has no result, or {@code restarted} when the same task started again later.
     */
    private String outcome;
}
