package de.mhus.vance.shared.magrathea.journal;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Workflow run started. Contains the frozen YAML snapshot — laufende
 * Runs lesen ausschließlich daraus, Source-Document-Edits beeinflussen
 * den Run nicht (plan §7).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartRecord implements JournalRecord {

    /** Workflow definition name. */
    private String workflowName;

    /** Workflow definition version at start time. */
    private @Nullable String workflowVersion;

    /** Frozen verbatim YAML — only authoritative reference for the run. */
    private String definitionYaml;

    /** Caller-supplied params, after defaulting. */
    private @Nullable Map<String, Object> params;

    /** Audit hint — user id, scheduler key, hook origin. */
    private @Nullable String startedBy;

    /**
     * Document path the definition came from, when the run was started
     * from a document rather than by name. Null for name-resolved starts,
     * where the path is implied by the cascade.
     *
     * <p>{@link #workflowName} alone stops identifying the source once a
     * run can begin anywhere: two {@code helloworld.yaml} in different
     * folders share a name. The path is what makes the audit trail answer
     * "which file was this".
     */
    private @Nullable String sourcePath;

    /** Parent workflow run id when this run was spawned via {@code workflow_task} (plan §4.7). */
    private @Nullable String parentMagratheaProcessId;

    /** State in the parent that triggered this sub-run. */
    private @Nullable String parentState;
}
