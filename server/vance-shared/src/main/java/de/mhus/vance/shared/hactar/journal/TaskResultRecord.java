package de.mhus.vance.shared.hactar.journal;

import tools.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Terminal record for a task. Triggers the
 * {@code HactarCompletionEventBus} reaction that evaluates transitions
 * and enqueues the next task (plan §4.0, §6.4).
 *
 * <p>Idempotent append: enforced by a Mongo unique index on
 * {@code (workflowRunId, taskId, type=TaskResultRecord)} so retries
 * after pod-crash cannot duplicate the result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResultRecord implements JournalRecord {

    private String state;
    private String taskId;

    /**
     * State-graph outcome — one of {@code success}, {@code failure},
     * or a custom positive outcome ({@code approved}, {@code rejected},
     * {@code fired}, …), or an error kind name from
     * {@link de.mhus.vance.api.hactar.HactarErrorKind} (lowercased).
     */
    private String outcome;

    /** Type-executor output as JSON. {@code storeAs:} reads from here. */
    private @Nullable JsonNode output;

    /** Set on failure outcomes — short human-readable error detail. */
    private @Nullable String errorMessage;

    /** Wall-clock duration between {@link TaskStartedRecord} and this record. */
    private long durationMs;
}
