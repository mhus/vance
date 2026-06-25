package de.mhus.vance.api.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A semantic engine reply — the worker's complete answer for one turn.
 *
 * <p>Unlike {@link MetricsPayload} / {@link PlanPayload} /
 * {@link StatusPayload}, this variant is also routed to the parent
 * process's pending inbox (when a parent exists) so the parent engine
 * can react to the reply on its next lane-turn. The client-facing path
 * stays in sync: clients see the reply via {@code PROCESS_PROGRESS}
 * just like any other progress kind.
 *
 * <p>Discipline: each REPLY is a self-contained result. No fragments,
 * no "part 1 of 2"; for live progress use {@link StatusPayload}. An
 * engine may emit multiple REPLYs over its lifetime (e.g. a
 * clarification question, later the final answer), but each one must
 * be independently meaningful to the parent.
 *
 * <p>See {@code planning/process-engine-reply-channel.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("progress")
public class ReplyPayload {

    /** Full reply text — the assistant message the engine produced. */
    private String content;

    /**
     * Timestamp of the user-input turn the emitting worker was
     * responding to. Lets a receiving parent distinguish a fresh reply
     * from a stale one (the Marvin-spawn / Ford-stale-relay bug
     * pattern). {@code null} for engine-driven replies or when no
     * triggering user-input could be identified.
     */
    private @Nullable Instant inResponseToAt;

    /**
     * Optional structured side-channel data — useful when a
     * deterministic parent (Vogon-style) needs to branch on a worker's
     * result without re-parsing the text. v1 typically leaves this
     * {@code null}.
     */
    private @Nullable Map<String, Object> payload;

    /**
     * When {@code true} this is a live working-log entry, not the
     * canonical turn reply — emitted by engines (Lunkwill) that narrate
     * between tool batches so the user can follow progress in real
     * time. Clients render interim replies visually dimmed and keep
     * scrolling; only the non-interim reply at turn-end carries the
     * worker's authoritative answer. Defaults to {@code false}.
     *
     * <p>Interim replies are not routed to a parent's pending inbox —
     * only canonical replies cross the worker→parent boundary. See
     * {@code specification/lunkwill-engine.md}.
     */
    private boolean interim;
}
