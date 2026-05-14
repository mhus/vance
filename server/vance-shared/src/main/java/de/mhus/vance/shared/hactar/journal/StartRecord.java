package de.mhus.vance.shared.hactar.journal;

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

    /** Parent workflow run id when this run was spawned via {@code workflow_task} (plan §4.7). */
    private @Nullable String parentHactarProcessId;

    /** State in the parent that triggered this sub-run. */
    private @Nullable String parentState;
}
