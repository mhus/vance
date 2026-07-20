package de.mhus.vance.api.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of a manual "Compact session" action. Mirrors the engine's
 * {@code CompactionResult}. A no-op (nothing left to compact — the active
 * history is already at or below the keep-recent anchor) is a normal,
 * expected outcome: {@code compacted=false} with a {@code reason}, NOT an
 * error.
 *
 * <p>{@code deferred=true} means the compaction was queued on the process
 * lane (a turn was running / the lane was busy) and will run between turns;
 * the concrete result is not yet known to the caller.
 *
 * <p>See {@code specification/public/session-compact.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("session")
public class SessionCompactResponse {

    /** {@code true} when a summary was written and messages were archived. */
    private boolean compacted;

    /** How many messages were folded into the summary (0 on no-op). */
    private int messagesCompacted;

    /** Size of the produced summary in characters (0 on no-op). */
    private int summaryChars;

    /** New ARCHIVED_CHAT memory id, or {@code null} on no-op. */
    private @Nullable String memoryId;

    /** Explanation on a no-op / deferred outcome (e.g. "history <= keepRecent"). */
    private @Nullable String reason;

    /** {@code true} when queued on the lane (busy) and the result is not yet known. */
    private boolean deferred;
}
