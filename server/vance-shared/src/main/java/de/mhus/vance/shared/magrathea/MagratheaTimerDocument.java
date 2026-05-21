package de.mhus.vance.shared.magrathea;

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
 * Pending timer in {@code magrathea_timers}. Pod-local
 * {@code MagratheaTimerScanner} (in {@code vance-brain}) scans for
 * {@code firedAt == null AND fireAt ≤ now} and publishes a
 * {@code TaskCompletedEvent} carrying {@link #firedOutcome} for
 * {@link #linkedTaskId}. The normal {@code onTaskCompleted} path then
 * advances the workflow.
 *
 * <p>Two use cases share this row shape (plan §4.5 and §4.4):
 *
 * <ul>
 *   <li><b>{@code timer_task}</b> — {@code linkedTaskId} = the timer
 *       task itself, {@link #firedOutcome} = {@code "fired"} →
 *       the YAML's {@code on.fired:} resolves the next state.</li>
 *   <li><b>{@code gate_task} timeout</b> — {@code linkedTaskId} = the
 *       gate task, {@link #firedOutcome} = {@code "timeout"} → the
 *       gate's {@code catch.timeout:} or a custom {@code onTimeout}
 *       state resolves.</li>
 * </ul>
 */
@Document(collection = "magrathea_timers")
@CompoundIndexes({
        @CompoundIndex(
                name = "fire_idx",
                def = "{ 'firedAt': 1, 'fireAt': 1 }"),
        @CompoundIndex(
                name = "linked_task_idx",
                def = "{ 'linkedTaskId': 1 }",
                unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MagratheaTimerDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";
    private String projectId = "";
    private String workflowRunId = "";

    /** The {@code magrathea_tasks} row this timer fires a completion for. */
    private String linkedTaskId = "";

    /** Outcome string published as {@code TaskCompletedEvent.outcome} when the timer fires. */
    private String firedOutcome = "fired";

    private @Nullable Instant fireAt;

    /** Set once the timer has been claimed by the scanner. {@code null} means pending. */
    private @Nullable Instant firedAt;

    private @Nullable Instant createdAt;

    @Version
    private @Nullable Long version;
}
