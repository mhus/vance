package de.mhus.vance.shared.magrathea.journal;

import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Overall run-status transition. Status of a run is determined by the
 * most recent {@code StatusRecord} (plan §3.2 — analog Nimbus'
 * {@code WorkflowContext.getStatus()}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusRecord implements JournalRecord {

    private MagratheaRunStatus status;

    /** Optional human-readable reason for the transition. */
    private @Nullable String reason;
}
