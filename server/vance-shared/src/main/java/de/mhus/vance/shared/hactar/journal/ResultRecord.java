package de.mhus.vance.shared.hactar.journal;

import tools.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Terminal payload of a workflow run. Written when a {@code terminal}
 * state is reached; carries the {@code result:} block declared in the
 * YAML (plan §4.8). Sub-workflow callers consume this via the
 * {@code HactarSubWorkflowCompletionListener} (plan §6.4).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultRecord implements JournalRecord {

    /** Terminal state name that produced this result. */
    private String state;

    /** Parsed {@code result:} payload. */
    private JsonNode result;
}
